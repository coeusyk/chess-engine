<#
.SYNOPSIS
    Phase 16 / P16-2: canonical 1T search execution baseline, native Windows only.

.DESCRIPTION
    Run this from an actual native Windows PowerShell terminal (Start Menu ->
    PowerShell, or Windows Terminal set to a PowerShell/pwsh profile), on a
    native C:\... checkout of this repository -- NOT from a WSL shell, and NOT
    by invoking powershell.exe from WSL (WSL interop). #227/#226's own
    convention treats WSL2 and native Windows as separate execution contexts
    for performance numbers, and this script's whole job is to produce the one
    that's authoritative.

    Verifies the working tree, builds the production JAR, records the
    environment (including evidence that this really is a native PowerShell
    process, not one reached through WSL interop), runs the canonical
    uninstrumented 31-position bench (--bench-raw) for the requested number of
    repetitions after an explicit discarded warm-up, captures per-position
    search-instrumentation detail via the existing (but normally DEBUG-gated)
    Searcher log line, takes a JFR attribution pass, and runs a small
    clock-bound UCI sanity check. Every artifact is written under
    tools/results/p16-2/<UTC timestamp>/ so nothing is silently overwritten
    between runs.

    This script does not interpret or grade the results -- it only runs the
    protocol and saves raw output. Reading the saved files and writing the
    frozen baseline doc (docs/architecture/research/phase16-p16-2-canonical-1t-baseline.md)
    is a separate step.

.PARAMETER Depth
    Bench depth for the canonical run. Default matches BenchRunner.DEFAULT_DEPTH (13).

.PARAMETER Repetitions
    Number of measured repetitions after warm-up. Default 7 (P16-2's stated minimum).

.PARAMETER SkipBuild
    Skip the mvn package step and reuse whatever JAR is already at
    engine-uci\target\engine-uci-<version>-SNAPSHOT.jar. Off by default -- P16-2
    wants the JAR built fresh from the verified commit.

.EXAMPLE
    cd C:\path\to\chess-engine
    git checkout phase/16-search-execution-qualification
    git pull
    .\tools\p16-2-native-baseline.ps1
#>
param(
    [int]$Depth = 13,
    [int]$Repetitions = 7,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = (Get-Location).Path
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
$outDir = Join-Path $repoRoot "tools\results\p16-2\$timestamp"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

function Write-Section($title) {
    Write-Host ""
    Write-Host "==== $title ====" -ForegroundColor Cyan
}

# ---------------------------------------------------------------------------
# 0. Evidence this is a real native PowerShell process, not WSL interop.
# ---------------------------------------------------------------------------
Write-Section "Process/environment evidence (native vs. WSL interop)"
$evidence = [ordered]@{
    "PSVersionTable.PSVersion"       = $PSVersionTable.PSVersion.ToString()
    "PSVersionTable.Platform"        = $PSVersionTable.Platform
    "PSVersionTable.OS"              = $PSVersionTable.OS
    "CurrentProcess.Path"            = (Get-Process -Id $PID).Path
    "CurrentDirectory"               = $repoRoot
    "ComputerName"                   = $env:COMPUTERNAME
    "OSVersion (Environment class)"  = [System.Environment]::OSVersion.VersionString
}
$evidence.GetEnumerator() | ForEach-Object { Write-Host ("{0,-32}: {1}" -f $_.Key, $_.Value) }
$evidence | ConvertTo-Json | Out-File -FilePath (Join-Path $outDir "00-process-evidence.json") -Encoding utf8

if ($repoRoot -like "\\wsl*" -or $repoRoot -like "*\wsl.localhost\*") {
    Write-Host "REFUSING: current directory is a WSL-mounted path ($repoRoot)." -ForegroundColor Red
    Write-Host "This script must run from a native C:\... checkout, not a WSL filesystem path accessed from Windows." -ForegroundColor Red
    exit 1
}
if ((Get-Process -Id $PID).Path -notlike "*\WindowsPowerShell\*" -and (Get-Process -Id $PID).Path -notlike "*\PowerShell\*pwsh.exe") {
    Write-Host "WARNING: current process path doesn't look like a standard Windows PowerShell/pwsh executable:" -ForegroundColor Yellow
    Write-Host "  $((Get-Process -Id $PID).Path)" -ForegroundColor Yellow
    Write-Host "Verify by hand that this is not being launched through WSL interop before trusting the results." -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# 1. Verify branch / HEAD / clean tree.
# ---------------------------------------------------------------------------
Write-Section "Git state"
$commitSha = (git rev-parse HEAD).Trim()
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
$status = git status --porcelain
Write-Host "Branch: $branch"
Write-Host "HEAD:   $commitSha"
if ($status) {
    Write-Host "Working tree is NOT clean:" -ForegroundColor Red
    Write-Host $status
    Write-Host "Refusing to establish a baseline from a dirty tree. Commit or stash first." -ForegroundColor Red
    exit 1
}
Write-Host "Working tree clean."
"branch=$branch`ncommit=$commitSha" | Out-File -FilePath (Join-Path $outDir "01-git-state.txt") -Encoding utf8

# ---------------------------------------------------------------------------
# 2. Verify the P16-1 XOR TT fix is present (do not proceed on a tree that
#    somehow lost it -- P16-2 depends on P16-1 being in effect).
# ---------------------------------------------------------------------------
Write-Section "P16-1 TT fix presence check"
$ttFile = "engine-core\src\main\java\coeusyk\game\chess\core\search\TranspositionTable.java"
$ttContent = Get-Content $ttFile -Raw
if ($ttContent -notmatch "derivedKey\s*=\s*check\s*\^\s*data") {
    Write-Host "REFUSING: $ttFile does not contain the expected XOR-checksum fix (derivedKey = check ^ data)." -ForegroundColor Red
    Write-Host "P16-2 must not run on a tree without the P16-1 fix. Stopping." -ForegroundColor Red
    exit 1
}
Write-Host "P16-1 XOR fix confirmed present in $ttFile."

# ---------------------------------------------------------------------------
# 3. Build the production UCI JAR.
# ---------------------------------------------------------------------------
Write-Section "Build"
if (-not $SkipBuild) {
    mvn -pl engine-core,engine-uci -am package -DskipTests 2>&1 | Tee-Object -FilePath (Join-Path $outDir "02-build.log")
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Build failed -- see 02-build.log. Stopping." -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "Skipping build (-SkipBuild passed)."
}
$jar = Get-ChildItem -Path "engine-uci\target" -Filter "engine-uci-*-SNAPSHOT.jar" | Select-Object -First 1
if (-not $jar) {
    Write-Host "Could not find engine-uci-*-SNAPSHOT.jar under engine-uci\target -- did the build succeed?" -ForegroundColor Red
    exit 1
}
$jarPath = $jar.FullName
$jarHash = (Get-FileHash -Path $jarPath -Algorithm SHA256).Hash
Write-Host "JAR:        $jarPath"
Write-Host "SHA-256:    $jarHash"

# ---------------------------------------------------------------------------
# 4. Environment record.
# ---------------------------------------------------------------------------
Write-Section "Environment"
$javaVersionOutput = & java -version 2>&1 | Out-String
Write-Host $javaVersionOutput
$cpu = Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1
$os  = Get-CimInstance -ClassName Win32_OperatingSystem
$env_record = [ordered]@{
    commit_sha              = $commitSha
    branch                  = $branch
    jar_path                = $jarPath
    jar_sha256              = $jarHash
    java_version_raw        = $javaVersionOutput.Trim()
    java_exe_resolved       = (Get-Command java).Source
    os_caption              = $os.Caption
    os_version              = $os.Version
    os_build_number         = $os.BuildNumber
    cpu_name                = $cpu.Name
    cpu_cores               = $cpu.NumberOfCores
    cpu_logical_processors  = $cpu.NumberOfLogicalProcessors
    cpu_max_clock_mhz       = $cpu.MaxClockSpeed
    jvm_flags               = "--add-modules jdk.incubator.vector"
    threads_uci_option      = 1
    hash_mb                 = "16 (BenchRunner.BENCH_HASH_MB, hardcoded; the CLI --bench/--bench-raw path does not read the UCI Hash setoption)"
    evaluator                = "Classical"
    bench_depth              = $Depth
    bench_corpus              = "BenchRunner.BENCH_FENS, 31 positions (Stockfish public-domain bench suite)"
    bench_corpus_source_sha256 = (Get-FileHash -Path "engine-uci\src\main\java\coeusyk\game\chess\uci\BenchRunner.java" -Algorithm SHA256).Hash
    timestamp_utc            = $timestamp
}
$env_record | ConvertTo-Json | Out-File -FilePath (Join-Path $outDir "03-environment.json") -Encoding utf8
Write-Host "IMPORTANT: verify java_version_raw above is the JDK 21 toolchain this project expects, not an unrelated JDK found first on PATH." -ForegroundColor Yellow

# ---------------------------------------------------------------------------
# 6/7. Canonical bench: explicit warm-up (discarded), then N measured reps,
#      uninstrumented (--bench-raw) for a production-like number.
# ---------------------------------------------------------------------------
Write-Section "Canonical bench: warm-up (discarded)"
& java --add-modules jdk.incubator.vector -jar $jarPath --bench-raw $Depth 2>&1 |
    Out-File -FilePath (Join-Path $outDir "04-warmup-discarded.txt") -Encoding utf8
Write-Host "Warm-up complete (output saved, not counted)."

Write-Section "Canonical bench: $Repetitions measured repetitions"
for ($i = 1; $i -le $Repetitions; $i++) {
    Write-Host "  Repetition $i / $Repetitions ..."
    & java --add-modules jdk.incubator.vector -jar $jarPath --bench-raw $Depth 2>&1 |
        Out-File -FilePath (Join-Path $outDir ("05-canonical-run-{0:D2}.txt" -f $i)) -Encoding utf8
}
Write-Host "All $Repetitions canonical repetitions complete."

# ---------------------------------------------------------------------------
# 8. Per-position instrumentation detail (existing Searcher [BENCH] debug
#    line, enabled via tools\logback-debug.xml; no new counters).
# ---------------------------------------------------------------------------
Write-Section "Per-position search-instrumentation detail"
& java "-Dlogback.configurationFile=tools\logback-debug.xml" --add-modules jdk.incubator.vector `
    -jar $jarPath --bench-raw $Depth 2>&1 |
    Out-File -FilePath (Join-Path $outDir "06-per-position-debug.txt") -Encoding utf8
Write-Host "Per-position debug detail saved (eval_pct/acc_pct will read 0.0 -- instrumentation is off in this run by design; see 07 for those)."

# ---------------------------------------------------------------------------
# 9. Attribution pass: JFR + the existing (instrumented) counters together,
#    clearly separate from the canonical numbers above.
# ---------------------------------------------------------------------------
Write-Section "Attribution pass (JFR + instrumented counters, NON-CANONICAL)"
$jfrFile = Join-Path $outDir "07-attribution.jfr"
& java "-Dlogback.configurationFile=tools\logback-debug.xml" --add-modules jdk.incubator.vector `
    "-XX:StartFlightRecording=filename=$jfrFile,settings=profile" `
    -jar $jarPath --bench $Depth 2>&1 |
    Out-File -FilePath (Join-Path $outDir "07-attribution-console.txt") -Encoding utf8
Write-Host "JFR recording saved to $jfrFile (open in JDK Mission Control, or run 'jfr print --events jdk.ExecutionSample $jfrFile' for a text summary)."
try {
    & jfr print --events jdk.ExecutionSample $jfrFile 2>&1 |
        Out-File -FilePath (Join-Path $outDir "07-attribution-jfr-print.txt") -Encoding utf8
    Write-Host "jfr print output saved to 07-attribution-jfr-print.txt."
} catch {
    Write-Host "'jfr' CLI not found on PATH -- skipped text summary, .jfr file is still saved for JMC analysis." -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# 11. Clock-bound sanity check: exercise the real UCI path under a small
#     bounded clock. Reliability check only, not Elo evidence.
# ---------------------------------------------------------------------------
Write-Section "Clock-bound UCI sanity check"
$uciScript = @"
uci
isready
ucinewgame
position startpos
go movetime 2000
quit
"@
$sanityOut = Join-Path $outDir "08-clock-bound-sanity.txt"
$uciScript | & java --add-modules jdk.incubator.vector -jar $jarPath 2>&1 |
    Tee-Object -FilePath $sanityOut
if (Select-String -Path $sanityOut -Pattern "^bestmove " -Quiet) {
    Write-Host "PASS: a legal 'bestmove' line was emitted." -ForegroundColor Green
} else {
    Write-Host "FAIL: no 'bestmove' line found in the sanity-check output -- inspect $sanityOut." -ForegroundColor Red
}

Write-Section "Done"
Write-Host "All artifacts saved under: $outDir"
Write-Host "Hand this directory back (or its contents) so the frozen P16-2 baseline doc can be written from real numbers."
