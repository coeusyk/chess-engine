<#
.SYNOPSIS
  Native Windows fixed-depth timing comparison: published v0.5.7 source vs v0.6.0.

.DESCRIPTION
  Builds each release from its own detached git worktree, checks that each build matches the
  published JAR class by class, compiles tools/ReleaseComparisonHarness.java once, then runs it over
  the 30-position corpus shared by both releases (depth 13, Hash=16 MB, one thread, Classical
  evaluation, Contempt=0, fixed JVM flags). One discarded warm-up per version, then -Pairs measured
  pairs in alternating order (A,B then B,A ...). Raw per-run output is kept.

  The script times fixed-depth search work. It does not measure playing strength, and it does not
  turn node counts into a speedup: the summary reports measured elapsed time only.

  Source identity is pinned. -TagA must resolve to -ExpectedCommitA (the source of the published
  v0.5.7 JAR, beaef361...). The v0.5.7 git tag points at an older commit (7b5b3a4...) and is refused.

  Run it from the repository root, in native Windows PowerShell 7 on an idle host. Do not run it in WSL.
  Use -ValidateOnly first: it checks every precondition without building or timing anything.

.EXAMPLE
  pwsh -NoProfile -File tools/release-comparison.ps1 -ValidateOnly
  pwsh -NoProfile -File tools/release-comparison.ps1 -OutDir tools/results/release-comparison-20261013
#>
param(
    [string]$OutDir = ("tools/results/release-comparison-" + (Get-Date -Format "yyyyMMddTHHmmss")),
    [string]$TagA = "beaef361d35160d768473af7d2c349719db8508b",
    [string]$ExpectedCommitA = "beaef361d35160d768473af7d2c349719db8508b",
    [string]$TagB = "v0.6.0",
    [string]$ExpectedCommitB = "bc3dc380e759526e8aa19cd8385af16ab22ac502",
    [int]$Pairs = 7,
    [int]$TimeoutSec = 1200,
    [int]$WarmupDepth = 8,
    [double]$MaxIdleCpuPercent = 5,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$ValidateOnly
)
$ErrorActionPreference = "Stop"

$StaleTagCommit = "7b5b3a4332193d28a1f668654806fac434f2cb8f"   # what the v0.5.7 git tag points at
$ResearchDir = "docs/architecture/research/release-comparison-v0.5.7-v0.6.0"
$Corpus = "$ResearchDir/common-corpus-30-release-vs-v0.6.0.fen"
$CorpusSha256 = "3544a8fbb6f79e4f3c153f979a98da4b62c4380a98e0d8b7dc0259992341e560"   # LF-normalized
$CorpusPositions = 30
$ExpectedNodes = @{ A = 59793662L; B = 20540625L }   # deterministic totals from the research report
$ExpectedEvaluator = @{ A = "Evaluator"; B = "ClassicalEvaluator" }
$ExpectedClassList = @{ A = "$ResearchDir/identity/classes-published-v0.5.7.sha256"; B = "$ResearchDir/identity/classes-published-v0.6.0.sha256" }
$Depth = 13; $HashMb = 16
$jvm = @("-Xms512m", "-Xmx512m", "-XX:+UseG1GC", "--add-modules", "jdk.incubator.vector")

function Fail([string]$m) { throw "release-comparison: $m" }
function Sha256Lf([string]$path) {   # hash with CRLF folded to LF so a Windows checkout cannot change it
    $text = [IO.File]::ReadAllText($path).Replace("`r", "")
    $bytes = [Text.Encoding]::UTF8.GetBytes($text)
    ([BitConverter]::ToString([Security.Cryptography.SHA256]::HashData($bytes)) -replace "-", "").ToLower()
}
function Sha256File([string]$path) { (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }
function Get-ClassListing([string]$jarPath) {   # same lines as: find . -name '*.class' | LC_ALL=C sort | xargs sha256sum
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $names = @($zip.Entries | Where-Object { $_.FullName.EndsWith(".class") } | ForEach-Object { $_.FullName })
        [Array]::Sort($names, [StringComparer]::Ordinal)
        foreach ($n in $names) {
            $stream = $zip.GetEntry($n).Open(); $ms = [IO.MemoryStream]::new(); $stream.CopyTo($ms); $stream.Dispose()
            "{0}  ./{1}" -f (([BitConverter]::ToString([Security.Cryptography.SHA256]::HashData($ms.ToArray())) -replace "-", "").ToLower()), $n
        }
    } finally { $zip.Dispose() }
}
function Write-Manifest {
    Get-ChildItem $OutDir -File -Recurse | Where-Object { $_.Name -ne "SHA256SUMS" -and $_.FullName -notlike "*$([IO.Path]::DirectorySeparatorChar)work$([IO.Path]::DirectorySeparatorChar)*" } |
        ForEach-Object { "{0}  {1}" -f (Sha256File $_.FullName), $_.FullName.Substring($OutDir.Length + 1).Replace("\", "/") } | Set-Content (Join-Path $OutDir "SHA256SUMS")
}
function Get-Median($v) { $s = @($v | Sort-Object); $n = $s.Count; if ($n % 2) { $s[($n - 1) / 2] } else { ($s[$n / 2 - 1] + $s[$n / 2]) / 2 } }

# ---- preconditions ---------------------------------------------------------------------------
if ($PSVersionTable.PSVersion.Major -lt 7) { Fail "PowerShell 7 or newer is required." }
if (-not $IsWindows) { Fail "Native Windows is required for timing evidence. Do not run this in WSL." }
if (-not (Test-Path .git) -or ((git rev-parse --show-toplevel) -replace "/", "\") -ne (Get-Location).Path) { Fail "Run from the repository root." }
if (-not $JavaHome) { Fail "Set JAVA_HOME (or pass -JavaHome) to a JDK 21 install." }
$java = Join-Path $JavaHome "bin/java"; $javac = Join-Path $JavaHome "bin/javac"
$env:JAVA_HOME = $JavaHome   # Maven reads JAVA_HOME, so -JavaHome must reach it too (process scope only)
foreach ($v in "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "MAVEN_OPTS") {
    if (Get-Item "Env:$v" -ErrorAction SilentlyContinue) { Fail "$v is set. Unset it so the JVM flags are exactly the recorded ones." }
}
$javaVersionLine = (& $java -version 2>&1 | Select-Object -First 1).ToString()
if ($javaVersionLine -notmatch '"21\.') { Fail "JDK 21 required, found: $javaVersionLine" }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { Fail "mvn is not on PATH." }
if ($Pairs -lt 3) { Fail "-Pairs must be at least 3." }

# Source identity: the tag name must not be able to substitute the stale v0.5.7 tag commit.
$commitA = (git rev-parse --verify "$TagA^{commit}").Trim(); $commitB = (git rev-parse --verify "$TagB^{commit}").Trim()
if ($commitA -eq $StaleTagCommit) { Fail "-TagA resolves to $StaleTagCommit, the commit the stale v0.5.7 git tag points at. The published v0.5.7 JAR was built from $ExpectedCommitA." }
if ($commitA -ne $ExpectedCommitA) { Fail "-TagA resolves to $commitA, expected $ExpectedCommitA." }
if ($commitB -ne $ExpectedCommitB) { Fail "-TagB resolves to $commitB, expected $ExpectedCommitB." }

# Exact benchmark corpus
if (-not (Test-Path $Corpus)) { Fail "Corpus file missing: $Corpus" }
if ((Sha256Lf $Corpus) -ne $CorpusSha256) { Fail "Corpus SHA-256 differs from the recorded value." }
if (@(Get-Content $Corpus | Where-Object { $_.Trim() }).Count -ne $CorpusPositions) { Fail "Corpus does not have $CorpusPositions positions." }
foreach ($f in $ExpectedClassList.Values + "tools/ReleaseComparisonHarness.java") { if (-not (Test-Path $f)) { Fail "Missing file: $f" } }

# Working-tree isolation and evidence preservation
if (git status --porcelain) { Fail "The main working tree is not clean. Commit or stash first, so the recorded script/harness hashes describe committed files." }
if ((Test-Path $OutDir) -and (Get-ChildItem $OutDir -Force | Select-Object -First 1)) { Fail "$OutDir already exists and is not empty. Evidence directories are never reused." }

if ($ValidateOnly) {
    "ValidateOnly OK: A=$commitA B=$commitB corpus=$CorpusSha256 $javaVersionLine"
    return
}

# ---- idle host -------------------------------------------------------------------------------
$busy = Get-Process java, javaw, mvn, javac -ErrorAction SilentlyContinue
if ($busy) { Fail "Java or Maven processes are running: $(($busy | ForEach-Object Name | Sort-Object -Unique) -join ', ')" }
$samples = (Get-Counter '\Processor Information(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 15).CounterSamples.CookedValue
$cpuMean = [math]::Round(($samples | Measure-Object -Average).Average, 2); $cpuMax = [math]::Round(($samples | Measure-Object -Maximum).Maximum, 2)
if ($cpuMean -gt $MaxIdleCpuPercent) { Fail "Host is not idle: mean CPU $cpuMean% over 15 s (limit $MaxIdleCpuPercent%). Close other programs, including WSL." }
if ((Read-Host "Mean CPU $cpuMean% (max $cpuMax%). Is the host otherwise idle (no games, builds, browsers, media)? Type 'idle' to continue") -ne "idle") { Fail "Idle-host confirmation not given." }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
$work = Join-Path $OutDir "work"; $raw = Join-Path $OutDir "raw"
New-Item -ItemType Directory -Force -Path $work, $raw | Out-Null
$corpusPath = (Resolve-Path $Corpus).Path
$log = Join-Path $OutDir "run.log"
function Log([string]$m) { $line = "$([DateTime]::UtcNow.ToString('o')) $m"; Add-Content $log $line; Write-Host $line }

$arms = [ordered]@{ A = @{ ref = $TagA; commit = $commitA }; B = @{ ref = $TagB; commit = $commitB } }
$identity = [ordered]@{}
$trees = @()
$leftover = @()
try {
    foreach ($arm in $arms.Keys) {
        $tree = Join-Path $work $arm
        git worktree add --detach $tree $arms[$arm].commit | Out-Null; $trees += $tree
        Push-Location $tree
        try {
            if ((git rev-parse HEAD).Trim() -ne $arms[$arm].commit) { Fail "Worktree $arm is not at the expected commit." }
            Log "building $arm at $($arms[$arm].commit)"
            mvn -B -q -pl "engine-core,engine-uci" -am package -DskipTests
            if ($LASTEXITCODE -ne 0) { Fail "Maven build failed for $arm." }
            if (git status --porcelain) { Fail "Worktree $arm changed during the build." }
            $jar = Get-ChildItem "engine-uci/target/engine-uci-*.jar" | Where-Object { $_.Name -notlike "original-*" } | Select-Object -First 1
            if (-not $jar) { Fail "No engine-uci JAR produced for $arm." }
        } finally { Pop-Location }

        # The build must contain exactly the classes of the published JAR.
        $classLines = Get-ClassListing $jar.FullName
        $expectedClasses = @(Get-Content $ExpectedClassList[$arm] | Where-Object { $_.Trim() })
        if ($classLines.Count -ne $expectedClasses.Count -or (Compare-Object $classLines $expectedClasses -CaseSensitive)) {
            Fail "Arm ${arm}: classes in the rebuilt JAR differ from the published JAR ($($ExpectedClassList[$arm]))."
        }
        $identity[$arm] = [ordered]@{ ref = $arms[$arm].ref; commit = $arms[$arm].commit; jar = $jar.Name; jar_bytes = $jar.Length
            jar_sha256 = (Sha256File $jar.FullName); classes = $classLines.Count; class_listing_matches_published = $true; jar_path = $jar.FullName }
        Log "arm $arm class listing matches the published JAR ($($classLines.Count) classes)"
    }

    $classes = Join-Path $work "harness-classes"; New-Item -ItemType Directory -Force -Path $classes | Out-Null
    & $javac --add-modules jdk.incubator.vector -cp $identity.B.jar_path -d $classes tools/ReleaseComparisonHarness.java
    if ($LASTEXITCODE -ne 0) { Fail "Harness compile failed." }

    # Environment and build identity are recorded before any timing, so a failed run still leaves them behind.
    $recorded = [ordered]@{}
    foreach ($k in $identity.Keys) { $o = [ordered]@{}; foreach ($f in $identity[$k].Keys) { if ($f -ne "jar_path") { $o[$f] = $identity[$k][$f] } }; $recorded[$k] = $o }
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    [ordered]@{
        utc = [DateTime]::UtcNow.ToString("o"); machine = [Environment]::MachineName; os = [Runtime.InteropServices.RuntimeInformation]::OSDescription
        cpu = $cpu.Name.Trim(); logical_processors = $cpu.NumberOfLogicalProcessors
        ram_gb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
        power_scheme = ((powercfg /getactivescheme) -replace '^.*:\s*[0-9a-f-]+\s*', '').Trim()
        powershell = $PSVersionTable.PSVersion.ToString(); java = $javaVersionLine; java_vendor = (& $java -XshowSettings:properties -version 2>&1 | Where-Object { $_ -match 'java.vendor =' } | Select-Object -First 1).ToString().Trim()
        maven = ((mvn -v | Select-Object -First 1) -replace '\s*\(.*$', ''); git = (git --version); jvm_flags = ($jvm -join " ")
        idle_check = [ordered]@{ mean_cpu_percent = $cpuMean; max_cpu_percent = $cpuMax; samples = 15; limit_percent = $MaxIdleCpuPercent }
        repo_head = (git rev-parse HEAD).Trim(); repo_clean_at_start = $true
        script_sha256 = (Sha256File "tools/release-comparison.ps1"); harness_sha256 = (Sha256File "tools/ReleaseComparisonHarness.java")
        corpus_sha256_lf = $CorpusSha256; expected_main_nodes = $ExpectedNodes; pairs = $Pairs; timeout_sec = $TimeoutSec; warmup_depth = $WarmupDepth
        arms = $recorded
    } | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $OutDir "environment.json")

    function Invoke-Run([string]$arm, [string]$label) {
        $tsv = Join-Path $raw "$label-$arm.tsv"; $err = Join-Path $raw "$label-$arm.stderr.txt"
        $psi = [Diagnostics.ProcessStartInfo]::new($java)
        foreach ($a in $jvm + @("-cp", ($classes + [IO.Path]::PathSeparator + $identity[$arm].jar_path), "ReleaseComparisonHarness", $corpusPath, "$Depth", "$HashMb", "$WarmupDepth")) { $psi.ArgumentList.Add($a) }
        $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true; $psi.UseShellExecute = $false
        $p = [Diagnostics.Process]::Start($psi)
        $out = $p.StandardOutput.ReadToEndAsync(); $errText = $p.StandardError.ReadToEndAsync()
        $timedOut = -not $p.WaitForExit($TimeoutSec * 1000)
        if ($timedOut) { $p.Kill($true) }
        $p.WaitForExit()
        [IO.File]::WriteAllText($tsv, $out.Result); [IO.File]::WriteAllText($err, $errText.Result)   # keep partial output even when the run failed
        if ($timedOut) { Fail "Run $label-$arm exceeded $TimeoutSec s and was killed; partial output is in raw/." }
        if ($p.ExitCode -ne 0) { Fail "Run $label-$arm exited with code $($p.ExitCode)." }
        $cfg = ($errText.Result -split "`r?`n" | Where-Object { $_ -like "CONFIG *" } | Select-Object -First 1)
        $want = "CONFIG depth=$Depth hash_mb=$HashMb contempt=0 threads=1 evaluator=$($ExpectedEvaluator[$arm]) instrumentation=* positions=$CorpusPositions warmup_depth=$WarmupDepth"
        if (-not $cfg -or $cfg -notlike $want) { Fail "Run $label-$arm reported an unexpected configuration: '$cfg'" }
        $rows = Import-Csv -Delimiter "`t" $tsv
        if ($rows.Count -ne $CorpusPositions) { Fail "Run $label-$arm returned $($rows.Count) positions." }
        $nodes = [long]($rows | Measure-Object -Property nodes -Sum).Sum
        if ($nodes -ne $ExpectedNodes[$arm]) { Fail "Run $label-$arm searched $nodes main nodes, expected $($ExpectedNodes[$arm]). The search or the corpus is not what was recorded." }
        $ns = [long]($rows | Measure-Object -Property elapsed_ns -Sum).Sum
        [pscustomobject]@{ label = $label; arm = $arm; nodes = $nodes; elapsed_ms = [math]::Round($ns / 1e6, 1); nps = [long][math]::Floor($nodes * 1e9 / $ns) }
    }

    Log "warm-up A"; Invoke-Run "A" "warmup" | Out-Null
    Log "warm-up B"; Invoke-Run "B" "warmup" | Out-Null
    $runs = @()
    for ($i = 1; $i -le $Pairs; $i++) {
        foreach ($arm in $(if ($i % 2 -eq 1) { "A", "B" } else { "B", "A" })) {
            $label = "pair{0:D2}" -f $i; Log "run $label $arm"
            $runs += Invoke-Run $arm $label
        }
    }
    $runs | Export-Csv -NoTypeInformation (Join-Path $OutDir "runs.csv")

    $summary = [ordered]@{ note = "Elapsed time of fixed-depth search work on 30 positions. Not a strength measurement." }
    foreach ($arm in "A", "B") {
        $r = @($runs | Where-Object arm -eq $arm); $el = @($r.elapsed_ms); $med = Get-Median $el
        $summary[$arm] = [ordered]@{ commit = $arms[$arm].commit; runs = $r.Count; main_nodes_each_run = $ExpectedNodes[$arm]
            median_elapsed_ms = $med; min_elapsed_ms = ($el | Measure-Object -Minimum).Minimum; max_elapsed_ms = ($el | Measure-Object -Maximum).Maximum
            mad_ms = (Get-Median ($el | ForEach-Object { [math]::Abs($_ - $med) })); median_nps = (Get-Median $r.nps) }
    }
    $ratios = foreach ($i in 1..$Pairs) { $l = "pair{0:D2}" -f $i; ($runs | Where-Object { $_.label -eq $l -and $_.arm -eq "B" }).elapsed_ms / ($runs | Where-Object { $_.label -eq $l -and $_.arm -eq "A" }).elapsed_ms }
    $summary["median_elapsed_ratio_B_over_A"] = [math]::Round((Get-Median $ratios), 4)
    $summary["pair_elapsed_ratios_B_over_A"] = @($ratios | ForEach-Object { [math]::Round($_, 4) })
    $summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutDir "summary.json")

    Log "done"
    Write-Manifest   # last write to the directory: run.log must not change after this
    $summary | ConvertTo-Json -Depth 5
} catch {
    Set-Content (Join-Path $OutDir "FAILED.txt") $_.Exception.Message
    Write-Manifest
    throw
} finally {
    foreach ($t in $trees) {
        $removal = git worktree remove --force $t 2>&1
        if ($LASTEXITCODE -ne 0) { $leftover += "$t : $removal" }
    }
    git worktree prune
}
if ($leftover) {
    Set-Content (Join-Path $OutDir "CLEANUP-FAILED.txt") ($leftover -join "`n")   # written after the manifest, so it is not listed there
    Write-Warning "Could not remove $($leftover.Count) temporary worktree(s). See CLEANUP-FAILED.txt and remove them with 'git worktree remove --force' before another run."
    exit 1
}
