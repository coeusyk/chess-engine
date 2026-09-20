<#
.SYNOPSIS
    Phase 17 Step 2: native-Windows PVS throughput gate.

.DESCRIPTION
    Run this from an actual native Windows PowerShell terminal, on a native
    C:\... checkout of this repository -- NOT from a WSL shell, and NOT by
    invoking powershell.exe from WSL (WSL interop). Per this project's own
    convention (P16-2's dev-entries), WSL2 and native Windows are treated as
    separate execution contexts for wall-clock performance numbers, and WSL
    interop is explicitly not accepted as a substitute for the authoritative
    measurement even though it technically launches a real Windows process.

    Uses the exact same canonical protocol as tools/p16-2-native-baseline.ps1
    (same machine class, same JDK, Threads=1, Hash=16MB, Classical evaluator,
    depth 13, the same 31-position BenchRunner suite, --bench-raw): one
    discarded warm-up, then N measured repetitions. Also captures one
    per-position debug-logged run (the existing Searcher [BENCH] DEBUG line,
    via tools\logback-debug.xml -- no new instrumentation) so position-level
    elapsed time is available for the position-30-vs-31 comparison, without
    the JFR attribution pass or clock-bound sanity check P16-2's script also
    took (out of scope for this narrower throughput gate).

    Every artifact is written under tools/results/p17-2/<UTC timestamp>/ so
    nothing is silently overwritten between runs. This script does not
    interpret or grade the results -- it only runs the protocol and saves
    raw output.

.PARAMETER Depth
    Bench depth for the canonical run. Default matches BenchRunner.DEFAULT_DEPTH (13).

.PARAMETER Repetitions
    Number of measured repetitions after warm-up. Default 7, matching P16-2.

.PARAMETER SkipBuild
    Skip the mvn package step and reuse whatever JAR is already at
    engine-uci\target\engine-uci-<version>-SNAPSHOT.jar. Off by default --
    this gate wants the JAR built fresh from the verified commit.

.EXAMPLE
    cd C:\path\to\chess-engine
    git checkout phase/17-pvs-experiment
    git pull
    .\tools\p17-2-native-throughput.ps1
#>
param(
    [int]$Depth = 13,
    [int]$Repetitions = 7,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = (Get-Location).Path
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
$outDir = Join-Path $repoRoot "tools\results\p17-2\$timestamp"

function Write-Section($title) {
    Write-Host ""
    Write-Host "==== $title ====" -ForegroundColor Cyan
}

# ---------------------------------------------------------------------------
# 0. Evidence this is a real native PowerShell process, not WSL interop.
#    Console-only at this point -- nothing written to disk yet.
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
# 1. Verify branch / HEAD / clean tree. Still console-only.
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
    Write-Host "Refusing to measure throughput from a dirty tree. Commit or stash first." -ForegroundColor Red
    exit 1
}
Write-Host "Working tree clean."

# ---------------------------------------------------------------------------
# 2. Verify the Phase 17 PVS implementation is present (do not proceed on a
#    tree that somehow lost it -- this gate measures the PVS build, not the
#    pre-PVS one).
# ---------------------------------------------------------------------------
Write-Section "Phase 17 PVS presence check"
$searcherFile = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$searcherContent = Get-Content $searcherFile -Raw
if ($searcherContent -notmatch "pvsZeroWindowProbes" -or $searcherContent -notmatch "pvsFullDepthVerifications") {
    Write-Host "REFUSING: $searcherFile does not contain the expected PVS counters (pvsZeroWindowProbes / pvsFullDepthVerifications)." -ForegroundColor Red
    Write-Host "This throughput gate must not run on a tree without the Phase 17 PVS implementation. Stopping." -ForegroundColor Red
    exit 1
}
Write-Host "Phase 17 PVS implementation confirmed present in $searcherFile."

# ---------------------------------------------------------------------------
# All pre-flight checks passed on a clean tree -- only now create the output
# directory and persist what steps 0/1/2 found.
# ---------------------------------------------------------------------------
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$evidence | ConvertTo-Json | Out-File -FilePath (Join-Path $outDir "00-process-evidence.json") -Encoding utf8
"branch=$branch`ncommit=$commitSha" | Out-File -FilePath (Join-Path $outDir "01-git-state.txt") -Encoding utf8

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
$jar = Get-ChildItem -Path "engine-uci\target" -Filter "engine-uci-*-SNAPSHOT.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
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
Write-Host "IMPORTANT: verify cpu_name matches the P16-2 baseline's machine (AMD Ryzen 7 7700X) if comparing wall-clock time directly; a different CPU invalidates the elapsed-time comparison even though node counts remain comparable." -ForegroundColor Yellow

# ---------------------------------------------------------------------------
# 5. Canonical bench: explicit warm-up (discarded), then N measured reps,
#    uninstrumented (--bench-raw) for a production-like number.
# ---------------------------------------------------------------------------
Write-Section "Canonical bench: warm-up (discarded)"
& java --add-modules jdk.incubator.vector -jar $jarPath --bench-raw $Depth *> (Join-Path $outDir "04-warmup-discarded.txt")
Write-Host "Warm-up complete (output saved, not counted)."

Write-Section "Canonical bench: $Repetitions measured repetitions"
for ($i = 1; $i -le $Repetitions; $i++) {
    Write-Host "  Repetition $i / $Repetitions ..."
    & java --add-modules jdk.incubator.vector -jar $jarPath --bench-raw $Depth *> (Join-Path $outDir ("05-canonical-run-{0:D2}.txt" -f $i))
}
Write-Host "All $Repetitions canonical repetitions complete."

# ---------------------------------------------------------------------------
# 6. Per-position instrumentation detail (existing Searcher [BENCH] debug
#    line, enabled via tools\logback-debug.xml; no new counters), so
#    position-level elapsed time is available for the position 30 vs 31
#    comparison without any code change.
# ---------------------------------------------------------------------------
Write-Section "Per-position search-instrumentation detail"
& java "-Dlogback.configurationFile=tools\logback-debug.xml" --add-modules jdk.incubator.vector `
    -jar $jarPath --bench-raw $Depth *> (Join-Path $outDir "06-per-position-debug.txt")
Write-Host "Per-position debug detail saved (per-position 'time=Xms' field at depth $Depth gives position-level elapsed time)."

Write-Section "Done"
Write-Host "All artifacts saved under: $outDir"
Write-Host "Hand this directory back (or its contents) so the Phase 17 Step 2 throughput gate can be evaluated from real numbers."
