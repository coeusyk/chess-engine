<#
.SYNOPSIS
    Phase 17 Step 4: frozen isolated SPRT, repaired PVS candidate vs. pre-PVS baseline.

.DESCRIPTION
    Run this from an actual native Windows PowerShell terminal, on a native C:\...
    checkout of this repository, not from a WSL shell, and not via WSL interop.
    This is a bounded wrapper around the existing tools/sprt.ps1 match runner: it does
    not reimplement match execution, it freezes every protocol parameter Phase 17's
    strength gate depends on, builds both JARs from exact, verified commits, verifies
    the opening corpus, records full environment/SHA-256 evidence, and only then calls
    tools/sprt.ps1 with those frozen parameters.

    THIS SCRIPT DOES NOT RUN UNLESS YOU PASS -IReallyMeanIt. Phase 17 Step 4 preparation
    explicitly stops before any SPRT game is played; this switch exists so the script
    cannot be sourced or dry-run accidentally into actually spending games.

    Candidate: built fresh from the current checkout's HEAD, which must resolve to the
    isPvNode-propagation repair commit's source (verified below by checking
    Searcher.java's content, not just the commit hash, since docs-only commits can sit
    on top of the repair without changing it).

    Baseline: built fresh from commit ebe513e (the develop merge commit Phase 17 itself
    branched from, and the same pre-PVS source that produced every "pre-PVS baseline"
    figure in this phase's Gate 1/Gate 2 evidence; see dev-entries/phase-17.md and
    docs/architecture/research/phase16-p16-3-intervention-preregistration.md section 8,
    "one isolated, same-baseline SPRT against the current Threads=1 build"). Built via a
    disposable git worktree so the current checkout is never disturbed.

    Frozen terms (do not override at the command line; if a term must change, that is a
    new experiment, not this one):
      TC              5+0.05          (docs/sprt-guidelines.md section 1, this project's
                                        standard single-change convention)
      elo0/elo1       0 / 50          (same convention; also explicitly named in
                                        phase16-p16-3-intervention-preregistration.md
                                        section 8 as "matching this project's existing
                                        SPRT convention")
      alpha/beta      0.05 / 0.05     (same convention)
      Threads         1 / 1           (this is a Threads=1 experiment; both engines)
      Hash            16 / 16         (MB; frozen explicitly rather than left at
                                        UciApplication's 64 MB UCI default, so the two
                                        engines are guaranteed identical rather than
                                        coincidentally identical; see the Gate 4
                                        preparation dev-entry for why 16 was chosen)
      Opening corpus  tools/noob_3moves.epd, order=random, plies=4, -repeat (paired
                                        colors from the same opening)
      Max games       20000           (docs/sprt-guidelines.md section 4's own worked
                                        example for this exact H0=0/H1=50 convention)
      Concurrency     2               (this project's existing sprt.ps1/nightly default;
                                        override only via -Concurrency if this machine's
                                        own throughput has been separately benchmarked
                                        with tools/benchmark_concurrency.ps1)

.PARAMETER IReallyMeanIt
    Required. Without this switch the script prints what it would do and exits 0
    without building anything or invoking cutechess-cli.

.PARAMETER BaselineRef
    Git ref to build the baseline JAR from. Default ebe513e (see DESCRIPTION). Override
    only if this exact Gate 4 protocol document has been amended to name a different
    frozen baseline before any game is played.

.PARAMETER Concurrency
    Cutechess -concurrency value. Default 2, matching this project's existing
    sprt.ps1/nightly-sprt.yml default. See DESCRIPTION.

.EXAMPLE
    cd C:\path\to\chess-engine
    git checkout phase/17-pvs-experiment
    git pull
    .\tools\p17-4-sprt.ps1 -IReallyMeanIt
#>
param(
    [switch]$IReallyMeanIt,
    [string]$BaselineRef = "ebe513e",
    [int]$Concurrency = 2
)

$ErrorActionPreference = "Stop"

$repoRoot = (Get-Location).Path
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
$outDir = Join-Path $repoRoot "tools\results\p17-4\$timestamp"

# Frozen protocol constants. Not parameters: changing any of these is a new experiment.
$TC             = "5+0.05"
$Elo0           = 0
$Elo1           = 50
$Alpha          = 0.05
$Beta           = 0.05
$EngineThreads  = 1
$HashMb         = 16
$MaxGames       = 20000
$OpeningsFile   = Join-Path $repoRoot "tools\noob_3moves.epd"
$ExpectedOpeningsSha256 = "2011193B4854E9A8CFDC05312CA2DBAFFA6CEAE3ABBDEE20E2EAD2A18A603347"

function Write-Section($title) {
    Write-Host ""
    Write-Host "==== $title ====" -ForegroundColor Cyan
}

# ---------------------------------------------------------------------------
# 0. Refuse anything but an explicit, deliberate invocation.
# ---------------------------------------------------------------------------
Write-Section "Phase 17 Step 4: frozen SPRT protocol (see this file's header comment)"
Write-Host "Candidate: current checkout HEAD (source must match the isPvNode-propagation repair)"
Write-Host "Baseline:  fresh build from $BaselineRef (disposable worktree)"
Write-Host "TC=$TC  elo0=$Elo0 elo1=$Elo1 alpha=$Alpha beta=$Beta  Threads=$EngineThreads  Hash=$HashMb  MaxGames=$MaxGames  Concurrency=$Concurrency"
Write-Host "Openings: $OpeningsFile (expected SHA-256 $ExpectedOpeningsSha256)"

if (-not $IReallyMeanIt) {
    Write-Host ""
    Write-Host "Dry-run only (pass -IReallyMeanIt to actually build JARs and start the match)." -ForegroundColor Yellow
    exit 0
}

# ---------------------------------------------------------------------------
# 1. Evidence this is native, not WSL interop. Console-only at this point.
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
    exit 1
}

# ---------------------------------------------------------------------------
# 2. Verify candidate source and clean tree. Refuse dirty/mismatched state.
# ---------------------------------------------------------------------------
Write-Section "Candidate git state"
$candidateSha = (git rev-parse HEAD).Trim()
$candidateBranch = (git rev-parse --abbrev-ref HEAD).Trim()
$status = git status --porcelain
Write-Host "Branch: $candidateBranch"
Write-Host "HEAD:   $candidateSha"
if ($status) {
    Write-Host "Working tree is NOT clean:" -ForegroundColor Red
    Write-Host $status
    Write-Host "Refusing to build the candidate from a dirty tree. Commit or stash first." -ForegroundColor Red
    exit 1
}

$searcherFile = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$searcherContent = Get-Content $searcherFile -Raw
if ($searcherContent -notmatch "boolean childIsPvNode = isPvNode;") {
    Write-Host "REFUSING: $searcherFile does not contain the repaired childIsPvNode expression (``boolean childIsPvNode = isPvNode;``)." -ForegroundColor Red
    Write-Host "This SPRT must measure the repaired candidate, not the pre-repair or some other tree. Stopping." -ForegroundColor Red
    exit 1
}
Write-Host "Repaired isPvNode expression confirmed present in $searcherFile."

# ---------------------------------------------------------------------------
# 3. Verify the opening corpus by content, not just presence.
# ---------------------------------------------------------------------------
Write-Section "Opening corpus verification"
if (-not (Test-Path $OpeningsFile)) {
    Write-Host "REFUSING: opening corpus not found at $OpeningsFile." -ForegroundColor Red
    Write-Host "This file is gitignored (not tracked in this repository); see the Gate 4 preparation" -ForegroundColor Red
    Write-Host "dev-entry for why. It must be present locally and verified by hash before any game runs;" -ForegroundColor Red
    Write-Host "silently falling back to no opening file (all games from startpos) is not accepted for" -ForegroundColor Red
    Write-Host "this protocol. Stopping." -ForegroundColor Red
    exit 1
}
$actualOpeningsSha256 = (Get-FileHash -Path $OpeningsFile -Algorithm SHA256).Hash
if ($actualOpeningsSha256 -ne $ExpectedOpeningsSha256) {
    Write-Host "REFUSING: opening corpus SHA-256 mismatch." -ForegroundColor Red
    Write-Host "Expected: $ExpectedOpeningsSha256" -ForegroundColor Red
    Write-Host "Actual:   $actualOpeningsSha256" -ForegroundColor Red
    Write-Host "A different local copy of this file would silently change the experiment. Stopping." -ForegroundColor Red
    exit 1
}
Write-Host "Opening corpus SHA-256 verified: $actualOpeningsSha256"

# All pre-flight checks passed. Only now create the output directory.
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$evidence | ConvertTo-Json | Out-File -FilePath (Join-Path $outDir "00-process-evidence.json") -Encoding utf8
"candidate_branch=$candidateBranch`ncandidate_commit=$candidateSha`nbaseline_ref=$BaselineRef" |
    Out-File -FilePath (Join-Path $outDir "01-git-state.txt") -Encoding utf8

# ---------------------------------------------------------------------------
# 4. Build the candidate JAR from the current checkout.
# ---------------------------------------------------------------------------
Write-Section "Build candidate (current checkout)"
mvn -pl engine-core,engine-uci -am package -DskipTests 2>&1 |
    Tee-Object -FilePath (Join-Path $outDir "02-build-candidate.log")
if ($LASTEXITCODE -ne 0) {
    Write-Host "Candidate build failed, see 02-build-candidate.log. Stopping." -ForegroundColor Red
    exit 1
}
$candidateJar = Get-ChildItem -Path "engine-uci\target" -Filter "engine-uci-*-SNAPSHOT.jar" |
    Where-Object { $_.Name -notlike "original-*" } | Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $candidateJar) {
    Write-Host "Could not find the candidate JAR under engine-uci\target." -ForegroundColor Red
    exit 1
}
$candidateJarPath = $candidateJar.FullName
$candidateJarSha256 = (Get-FileHash -Path $candidateJarPath -Algorithm SHA256).Hash
Write-Host "Candidate JAR: $candidateJarPath"
Write-Host "SHA-256:       $candidateJarSha256"

# ---------------------------------------------------------------------------
# 5. Build the baseline JAR from $BaselineRef via a disposable worktree, so the
#    current checkout (and its candidate JAR) is never touched.
# ---------------------------------------------------------------------------
Write-Section "Build baseline ($BaselineRef, disposable worktree)"
$worktreeDir = Join-Path $env:TEMP "p17-4-baseline-$timestamp"
git worktree add --detach $worktreeDir $BaselineRef 2>&1 | Tee-Object -FilePath (Join-Path $outDir "03-worktree-add.log")
if ($LASTEXITCODE -ne 0) {
    Write-Host "git worktree add failed, see 03-worktree-add.log. Stopping." -ForegroundColor Red
    exit 1
}
try {
    $baselineActualSha = (git -C $worktreeDir rev-parse HEAD).Trim()
    "baseline_actual_commit=$baselineActualSha" | Out-File -FilePath (Join-Path $outDir "01-git-state.txt") -Append -Encoding utf8

    Push-Location $worktreeDir
    try {
        mvn -pl engine-core,engine-uci -am package -DskipTests 2>&1 |
            Tee-Object -FilePath (Join-Path $outDir "04-build-baseline.log")
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Baseline build failed, see 04-build-baseline.log. Stopping." -ForegroundColor Red
            exit 1
        }
        $baselineJarSrc = Get-ChildItem -Path "engine-uci\target" -Filter "engine-uci-*-SNAPSHOT.jar" |
            Where-Object { $_.Name -notlike "original-*" } | Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if (-not $baselineJarSrc) {
            Write-Host "Could not find the baseline JAR under $worktreeDir\engine-uci\target." -ForegroundColor Red
            exit 1
        }
    } finally {
        Pop-Location
    }

    $baselineJarPath = Join-Path $outDir "baseline-$baselineActualSha.jar"
    Copy-Item -Path $baselineJarSrc.FullName -Destination $baselineJarPath -Force
    $baselineJarSha256 = (Get-FileHash -Path $baselineJarPath -Algorithm SHA256).Hash
    Write-Host "Baseline JAR: $baselineJarPath"
    Write-Host "SHA-256:      $baselineJarSha256"
} finally {
    git worktree remove --force $worktreeDir 2>&1 | Out-Null
}

# ---------------------------------------------------------------------------
# 6. Environment record (mirrors tools/p17-2-native-throughput.ps1).
# ---------------------------------------------------------------------------
Write-Section "Environment"
$javaVersionOutput = & java -version 2>&1 | Out-String
Write-Host $javaVersionOutput
$cpu = Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1
$os  = Get-CimInstance -ClassName Win32_OperatingSystem
$env_record = [ordered]@{
    candidate_commit        = $candidateSha
    candidate_branch        = $candidateBranch
    candidate_jar_sha256    = $candidateJarSha256
    baseline_ref            = $BaselineRef
    baseline_actual_commit  = $baselineActualSha
    baseline_jar_sha256     = $baselineJarSha256
    openings_file           = $OpeningsFile
    openings_sha256         = $actualOpeningsSha256
    java_version_raw        = $javaVersionOutput.Trim()
    os_caption              = $os.Caption
    cpu_name                = $cpu.Name
    tc                      = $TC
    elo0                    = $Elo0
    elo1                    = $Elo1
    alpha                   = $Alpha
    beta                    = $Beta
    threads_per_engine      = $EngineThreads
    hash_mb_per_engine      = $HashMb
    max_games               = $MaxGames
    concurrency             = $Concurrency
    timestamp_utc           = $timestamp
}
$env_record | ConvertTo-Json | Out-File -FilePath (Join-Path $outDir "05-environment.json") -Encoding utf8
Write-Host "IMPORTANT: verify java_version_raw above is the JDK 21 toolchain this project expects." -ForegroundColor Yellow

# ---------------------------------------------------------------------------
# 7. Hand off to the existing, already-tested match runner with frozen terms.
#    This is the only step that spends games. Everything above is prep/evidence.
# ---------------------------------------------------------------------------
Write-Section "Invoking tools\sprt.ps1 with frozen Phase 17 Step 4 terms"
$sprtLog = Join-Path $outDir "06-sprt-console.log"
& (Join-Path $repoRoot "tools\sprt.ps1") `
    -New $candidateJarPath `
    -Old $baselineJarPath `
    -Tag "phase17-pvs" `
    -Elo0 $Elo0 -Elo1 $Elo1 -Alpha $Alpha -Beta $Beta `
    -TC $TC `
    -Concurrency $Concurrency `
    -EngineThreads $EngineThreads `
    -MaxGames $MaxGames `
    -OpeningsFile $OpeningsFile `
    -NewOptions "Hash=$HashMb" `
    -OldOptions "Hash=$HashMb" `
    2>&1 | Tee-Object -FilePath $sprtLog

$sprtOutputText = Get-Content -Path $sprtLog -Raw
if ($sprtOutputText -match 'H1 was accepted') {
    $verdict = 'H1_ACCEPTED'
} elseif ($sprtOutputText -match 'H0 was accepted') {
    $verdict = 'H0_ACCEPTED'
} else {
    $verdict = 'INCONCLUSIVE'
}

Write-Section "Done"
Write-Host "Verdict: $verdict"
if ($verdict -eq 'H1_ACCEPTED') {
    Write-Host "H1 accepted: evidence favors elo1=$Elo1 over elo0=$Elo0 under this SPRT."
} elseif ($verdict -eq 'H0_ACCEPTED') {
    Write-Host "H0 accepted: evidence favors elo0=$Elo0 over elo1=$Elo1 under this SPRT."
    Write-Host "This does NOT by itself establish the candidate is weaker than baseline. It means a" -ForegroundColor Yellow
    Write-Host "+$Elo1 Elo gain was not demonstrated. Do not report this as a confirmed regression." -ForegroundColor Yellow
} else {
    Write-Host "Inconclusive: neither boundary was reached before the $MaxGames-game cap."
}
Write-Host "All artifacts saved under: $outDir"
