<#
.SYNOPSIS
  Native fixed-depth throughput comparison of two release tags (default: published v0.5.7 source vs v0.6.0).

.DESCRIPTION
  Builds each tag from its own detached git worktree (the checked-out sources are never modified),
  compiles tools/ReleaseComparisonHarness.java once, then runs the harness over the 30-position
  corpus shared by both releases at depth 13, Hash=16 MB, one thread, Classical evaluation, with fixed JVM flags.
  One discarded warm-up per version, then N measured pairs. Pair order alternates (A,B / B,A / ...)
  to spread drift across both versions. Raw TSV output for every run is kept.

  This measures timing. Run it only in native Windows PowerShell on an idle host. Do not run it in WSL.
  Node counts are deterministic and are compared separately (see the research report).

.EXAMPLE
  pwsh -NoProfile -File tools/release-comparison.ps1 -OutDir tools/results/release-comparison-<stamp>
#>
param(
    [string]$OutDir = ("tools/results/release-comparison-" + (Get-Date -Format "yyyyMMddTHHmmss")),
    [string]$TagA = "beaef361d35160d768473af7d2c349719db8508b",  # source of the published v0.5.7 release; the v0.5.7 tag is older
    [string]$TagB = "v0.6.0",
    [int]$Pairs = 7,
    [string]$Corpus = "docs/architecture/research/release-comparison-v0.5.7-v0.6.0/common-corpus-30-release-vs-v0.6.0.fen",
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = "Stop"
if (-not $JavaHome) { throw "Set JAVA_HOME (or pass -JavaHome) to a JDK 21 install." }
$java = Join-Path $JavaHome "bin/java"; $javac = Join-Path $JavaHome "bin/javac"
$env:JAVA_HOME = $JavaHome   # Maven reads JAVA_HOME, so -JavaHome must reach it too (process scope only)
$jvm = @("-Xms512m", "-Xmx512m", "-XX:+UseG1GC", "--add-modules", "jdk.incubator.vector")
if ($IsWindows -eq $false) { throw "Native Windows required for timing evidence." }
foreach ($v in "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "MAVEN_OPTS") {
    if (Get-Item "Env:$v" -ErrorAction SilentlyContinue) { throw "$v is set; unset it so JVM flags are exactly the recorded ones." }
}
$answer = Read-Host "Is the host idle (no games, builds, browsers, media)? Type 'idle' to continue"
if ($answer -ne "idle") { throw "Idle-host confirmation not given." }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
$work = Join-Path $OutDir "work"; $raw = Join-Path $OutDir "raw"
New-Item -ItemType Directory -Force -Path $work, $raw | Out-Null
$corpusPath = (Resolve-Path $Corpus).Path

$jars = @{}; $identity = [ordered]@{}
foreach ($tag in @($TagA, $TagB)) {
    $tree = Join-Path $work $tag
    git worktree add --detach $tree $tag | Out-Null
    Push-Location $tree
    try {
        $commit = (git rev-parse HEAD).Trim()
        mvn -B -q -pl engine-core,engine-uci -am package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Build failed for $tag" }
        if ((git status --short | Measure-Object).Count -ne 0) { throw "Worktree for $tag is not clean after build" }
        $jar = Get-ChildItem "engine-uci/target/engine-uci-*.jar" | Where-Object { $_.Name -notlike "original-*" } | Select-Object -First 1
        $jars[$tag] = $jar.FullName
        $identity[$tag] = [ordered]@{ commit = $commit; jar = $jar.Name; sha256 = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash.ToLower() }
    } finally { Pop-Location }
}
$classes = Join-Path $work "harness-classes"; New-Item -ItemType Directory -Force -Path $classes | Out-Null
& $javac --add-modules jdk.incubator.vector -cp $jars[$TagB] -d $classes tools/ReleaseComparisonHarness.java
if ($LASTEXITCODE -ne 0) { throw "Harness compile failed" }

function Invoke-Run([string]$tag, [string]$label) {
    $tsv = Join-Path $raw "$label-$tag.tsv"; $err = Join-Path $raw "$label-$tag.stderr.txt"
    $cp = "$classes$([IO.Path]::PathSeparator)$($jars[$tag])"
    & $java @jvm -cp $cp ReleaseComparisonHarness $corpusPath 13 16 > $tsv 2> $err
    if ($LASTEXITCODE -ne 0) { throw "Run failed: $label $tag" }
    $rows = Import-Csv -Delimiter "`t" $tsv
    $nodes = ($rows | Measure-Object -Property nodes -Sum).Sum
    $ns = ($rows | Measure-Object -Property elapsed_ns -Sum).Sum
    [pscustomobject]@{ label = $label; tag = $tag; nodes = [long]$nodes; elapsed_ms = [math]::Round($ns / 1e6, 1); nps = [long][math]::Floor($nodes * 1e9 / $ns) }
}

Invoke-Run $TagA "warmup" | Out-Null; Invoke-Run $TagB "warmup" | Out-Null
$runs = @()
for ($i = 1; $i -le $Pairs; $i++) {
    $order = if ($i % 2 -eq 1) { @($TagA, $TagB) } else { @($TagB, $TagA) }
    foreach ($tag in $order) { $runs += Invoke-Run $tag ("pair{0:D2}" -f $i) }
}
$runs | Export-Csv -NoTypeInformation (Join-Path $OutDir "runs.csv")

function Get-Median($v) { $s = $v | Sort-Object; $n = $s.Count; if ($n % 2) { $s[($n - 1) / 2] } else { ($s[$n / 2 - 1] + $s[$n / 2]) / 2 } }
$summary = [ordered]@{}
foreach ($tag in @($TagA, $TagB)) {
    $r = $runs | Where-Object tag -eq $tag
    $el = $r.elapsed_ms; $med = Get-Median $el
    $mad = Get-Median ($el | ForEach-Object { [math]::Abs($_ - $med) })
    $summary[$tag] = [ordered]@{
        nodes_per_run = ($r.nodes | Select-Object -Unique) -join ","; runs = $r.Count
        median_elapsed_ms = $med; min_elapsed_ms = ($el | Measure-Object -Minimum).Minimum; max_elapsed_ms = ($el | Measure-Object -Maximum).Maximum
        mad_ms = $mad; median_nps = Get-Median $r.nps
    }
}
$summary["elapsed_ratio_B_over_A"] = [math]::Round($summary[$TagB].median_elapsed_ms / $summary[$TagA].median_elapsed_ms, 4)
$summary["identity"] = $identity
$summary["java"] = (& $java -version 2>&1 | Select-Object -First 1)
$summary["jvm_flags"] = $jvm -join " "
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutDir "summary.json")
Get-FileHash (Get-ChildItem $raw -File) -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLower())  raw/$(Split-Path $_.Path -Leaf)" } | Set-Content (Join-Path $OutDir "SHA256SUMS")

foreach ($tag in @($TagA, $TagB)) { git worktree remove --force (Join-Path $work $tag) }
$summary | ConvertTo-Json -Depth 5
