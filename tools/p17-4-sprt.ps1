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

    Candidate: built fresh from the current checkout's HEAD. Candidate *source identity* is
    verified against the frozen candidate commit f9b152c (the isPvNode-propagation repair),
    not against HEAD itself, since legitimate docs/tooling-only commits (e6afbb1, f1b40a2,
    8797512) sit above f9b152c and must remain source-identical to it for production engine
    code. The check is a `git diff --quiet f9b152c -- <production source paths>` against every
    path that can affect engine behavior (see $ProductionSourcePaths below and its rationale);
    it fails closed on any non-empty diff, not merely on a commit-hash mismatch. The prior,
    weaker check (grepping Searcher.java for the repaired expression) is retained only as a
    secondary, more specific diagnostic when the source-tree check already fails; it is no
    longer the primary or sole identity check.

    Baseline: built fresh from commit ebe513e, frozen as the full resolved SHA
    ebe513eabd50e853a4e24a0260c64b41a5a4b224 (the develop merge commit Phase 17 itself branched
    from, and the same pre-PVS source that produced every "pre-PVS baseline" figure in this
    phase's Gate 1/Gate 2 evidence; see dev-entries/phase-17.md and
    docs/architecture/research/phase16-p16-3-intervention-preregistration.md section 8, "one
    isolated, same-baseline SPRT against the current Threads=1 build"). This is a frozen
    internal constant, not a command-line parameter: before building, the script resolves the
    baseline ref and refuses to proceed unless it resolves to exactly this full SHA. Built via
    a disposable git worktree so the current checkout is never disturbed. A different baseline
    requires a preregistration amendment and a code change to this script before game 1,
    exactly like concurrency (see the Gate 4 concurrency-amendment dev-entry for the precedent).

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
      Concurrency     6               (target host: AMD Ryzen 7 7700X, 8 physical cores /
                                        16 logical threads. Each engine instance is
                                        Threads=1, so 6 simultaneous games is an
                                        operational capacity choice, not a claim that one
                                        game maps exactly to one physical core, since engine
                                        processes, OS scheduling, SMT, and the idle side
                                        of each game all complicate that mapping. 6 stays
                                        below the 8 physical cores, leaving roughly two
                                        physical cores of scheduling headroom for
                                        Windows, JVM/process overhead, cutechess-cli
                                        itself, and interactive desktop use during a
                                        potentially long run, deliberately not attempting
                                        to saturate all 16 logical/SMT threads. It does not
                                        change the mathematical elo0/elo1/alpha/beta SPRT
                                        bounds, but it is still part of the experimental
                                        conditions, not a free knob: at a wall-clock TC like
                                        5+0.05, CPU contention under a given concurrency can
                                        change effective compute available per move, which
                                        can affect observed game outcomes, particularly if
                                        the two candidates being compared have different
                                        search-efficiency characteristics. It is frozen the
                                        same as every other term below precisely because of
                                        that: fixed before game 1, not overridable at the
                                        command line, not changeable mid-run.)
      cutechess-cli   see $CutechessVersion below (verified against the installed binary
                                        before any game runs; see section 6a)

.PARAMETER IReallyMeanIt
    Required. Without this switch the script prints what it would do and exits 0
    without building anything or invoking cutechess-cli.

.EXAMPLE
    cd C:\path\to\chess-engine
    git checkout phase/17-pvs-experiment
    git pull
    .\tools\p17-4-sprt.ps1 -IReallyMeanIt
#>
param(
    [switch]$IReallyMeanIt
)

$ErrorActionPreference = "Stop"

$repoRoot = (Get-Location).Path
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
$outDir = Join-Path $repoRoot "tools\results\p17-4\$timestamp"

# Frozen protocol constants. Not parameters (BaselineRef was removed as a command-line
# override; see section 5 below): changing any of these is a new experiment.
$TC             = "5+0.05"
$Elo0           = 0
$Elo1           = 50
$Alpha          = 0.05
$Beta           = 0.05
$EngineThreads  = 1
$HashMb         = 16
$MaxGames       = 20000
$Concurrency    = 6
$OpeningsFile   = Join-Path $repoRoot "tools\noob_3moves.epd"
$ExpectedOpeningsSha256 = "2011193B4854E9A8CFDC05312CA2DBAFFA6CEAE3ABBDEE20E2EAD2A18A603347"

# Frozen candidate source identity: the isPvNode-propagation repair commit. HEAD may sit
# above this on legitimate docs/tooling-only commits; production source must not.
$CandidateRef = "f9b152ca4f45e8e8aa5a48092b03416aba79b230"

# Frozen baseline: the develop merge commit this phase branched from, pinned to its full
# resolved SHA (not the short form) so an accidental short-hash collision elsewhere in
# history cannot silently resolve to the wrong commit.
$BaselineRef = "ebe513eabd50e853a4e24a0260c64b41a5a4b224"

# Production source paths that can affect the packaged UCI engine's behavior, derived from
# the actual build rather than assumed: engine-core has no src/main/resources at all;
# engine-uci/src/main/resources contains books/Performance.bin (the built-in opening book,
# loaded by UciApplication's BookFile option, default OwnBook=false so unused unless
# explicitly enabled) and logback.xml (logging config only). engine-uci is built as a
# shaded/fat jar (maven-shade-plugin) pulling in engine-core's compiled classes, so both
# modules' src/main trees are in scope; no other module contributes to this jar.
$ProductionSourcePaths = @(
    "engine-core/src/main",
    "engine-uci/src/main"
)

# Frozen cutechess-cli version. Bumped from the originally-frozen v1.4.0 to the latest
# release (checked 2026-09-20 via `gh api repos/cutechess/cutechess/releases/latest`); the
# v1.4.0 -> v1.5.1 changelog contains only bug fixes and a Qt 5 -> Qt 6 build-tooling change,
# nothing affecting SPRT/game-management logic for a standard-variant UCI match, so there is
# no reproducibility reason to stay pinned to the older release.
$CutechessVersion = "1.5.1"

function Write-Section($title) {
    Write-Host ""
    Write-Host "==== $title ====" -ForegroundColor Cyan
}

# ---------------------------------------------------------------------------
# 0. Refuse anything but an explicit, deliberate invocation.
# ---------------------------------------------------------------------------
Write-Section "Phase 17 Step 4: frozen SPRT protocol (see this file's header comment)"
Write-Host "Candidate: current checkout HEAD (production source must be identical to $CandidateRef)"
Write-Host "Baseline:  fresh build from $BaselineRef (frozen full SHA, disposable worktree)"
Write-Host "TC=$TC  elo0=$Elo0 elo1=$Elo1 alpha=$Alpha beta=$Beta  Threads=$EngineThreads  Hash=$HashMb  MaxGames=$MaxGames  Concurrency=$Concurrency  cutechess-cli=$CutechessVersion"
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

$candidateSourceDiff = git diff --stat "$CandidateRef" -- $ProductionSourcePaths
$candidateSourceIdentical = ($LASTEXITCODE -eq 0) -and (-not $candidateSourceDiff)
if (-not $candidateSourceIdentical) {
    Write-Host "REFUSING: production source has drifted from the frozen candidate $CandidateRef." -ForegroundColor Red
    Write-Host "Diff (git diff --stat $CandidateRef -- $($ProductionSourcePaths -join ' ')):" -ForegroundColor Red
    Write-Host $candidateSourceDiff -ForegroundColor Red
    Write-Host "This SPRT must measure exactly the frozen candidate's production source, not a" -ForegroundColor Red
    Write-Host "checkout that has drifted from it (even a legitimate later commit). Stopping." -ForegroundColor Red
    exit 1
}
Write-Host "Production source confirmed identical to frozen candidate $CandidateRef (checked: $($ProductionSourcePaths -join ', '))."

# Secondary, more specific diagnostic: only meaningful once the source-tree check above has
# already passed. Not the primary identity check (see the header comment for why).
$searcherFile = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$searcherContent = Get-Content $searcherFile -Raw
if ($searcherContent -notmatch "boolean childIsPvNode = isPvNode;") {
    Write-Host "REFUSING: $searcherFile does not contain the repaired childIsPvNode expression (``boolean childIsPvNode = isPvNode;``), despite the source-tree check above passing." -ForegroundColor Red
    Write-Host "This should not be reachable if the source-tree check is correct; treat it as a bug in this script's path list, not as evidence the candidate is fine. Stopping." -ForegroundColor Red
    exit 1
}
Write-Host "Repaired isPvNode expression confirmed present in $searcherFile (secondary diagnostic)."

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
"candidate_branch=$candidateBranch`ncandidate_commit=$candidateSha`ncandidate_frozen_ref=$CandidateRef`ncandidate_source_identical_to_frozen_ref=$candidateSourceIdentical`nbaseline_frozen_ref=$BaselineRef" |
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
$resolvedBaselineSha = (git rev-parse $BaselineRef 2>&1)
if ($LASTEXITCODE -ne 0 -or $resolvedBaselineSha.Trim() -ne $BaselineRef) {
    Write-Host "REFUSING: baseline ref $BaselineRef did not resolve to itself as a full SHA." -ForegroundColor Red
    Write-Host "git rev-parse output: $resolvedBaselineSha" -ForegroundColor Red
    Write-Host "This means the frozen baseline commit is not present in this checkout's history" -ForegroundColor Red
    Write-Host "(fetch it first) or the frozen constant above no longer names a real commit." -ForegroundColor Red
    exit 1
}
Write-Host "Baseline ref resolved and verified: $resolvedBaselineSha"

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
# 6a. Verify the installed cutechess-cli matches the frozen version. Fail closed: this
#     protocol must not silently run under an arbitrary installed version.
# ---------------------------------------------------------------------------
Write-Section "cutechess-cli version verification"
$cutechessPath = $env:CUTECHESS
if (-not $cutechessPath) {
    $cutechessCmd = Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue
    $cutechessPath = if ($cutechessCmd) { $cutechessCmd.Source } else { $null }
}
if (-not $cutechessPath -or -not (Test-Path $cutechessPath)) {
    Write-Host "REFUSING: cutechess-cli not found (set `$env:CUTECHESS or add it to PATH)." -ForegroundColor Red
    exit 1
}
$cutechessVersionOutput = (& $cutechessPath --version 2>&1 | Out-String).Trim()
Write-Host "cutechess-cli path:   $cutechessPath"
Write-Host "cutechess-cli --version output: $cutechessVersionOutput"
if ($cutechessVersionOutput -match [regex]::Escape($CutechessVersion)) {
    $cutechessVersionVerified = $true
    Write-Host "Verified: installed cutechess-cli reports version $CutechessVersion."
} else {
    $cutechessVersionVerified = $false
    Write-Host "REFUSING: cutechess-cli --version output does not contain the frozen version string '$CutechessVersion'." -ForegroundColor Red
    Write-Host "Expected version: $CutechessVersion" -ForegroundColor Red
    Write-Host "Actual output:    $cutechessVersionOutput" -ForegroundColor Red
    Write-Host "This protocol must not run under an unverified cutechess-cli version. Install the" -ForegroundColor Red
    Write-Host "frozen version, or amend this protocol document and this script's `$CutechessVersion" -ForegroundColor Red
    Write-Host "constant first if a different version is now the intended frozen one. Stopping." -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# 6b. Environment record (mirrors tools/p17-2-native-throughput.ps1).
# ---------------------------------------------------------------------------
Write-Section "Environment"
$javaVersionOutput = & java -version 2>&1 | Out-String
Write-Host $javaVersionOutput
$cpu = Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1
$os  = Get-CimInstance -ClassName Win32_OperatingSystem
$env_record = [ordered]@{
    candidate_commit                    = $candidateSha
    candidate_branch                    = $candidateBranch
    candidate_frozen_ref                = $CandidateRef
    candidate_source_identical_to_frozen_ref = $candidateSourceIdentical
    candidate_jar_sha256                = $candidateJarSha256
    baseline_frozen_ref                 = $BaselineRef
    baseline_actual_commit              = $baselineActualSha
    baseline_jar_sha256                 = $baselineJarSha256
    openings_file                       = $OpeningsFile
    openings_sha256                     = $actualOpeningsSha256
    cutechess_path                      = $cutechessPath
    cutechess_version_expected          = $CutechessVersion
    cutechess_version_output            = $cutechessVersionOutput
    cutechess_version_verified          = $cutechessVersionVerified
    java_version_raw                    = $javaVersionOutput.Trim()
    os_caption                          = $os.Caption
    cpu_name                            = $cpu.Name
    tc                                  = $TC
    elo0                                = $Elo0
    elo1                                = $Elo1
    alpha                               = $Alpha
    beta                                = $Beta
    threads_per_engine                  = $EngineThreads
    hash_mb_per_engine                  = $HashMb
    max_games                           = $MaxGames
    concurrency                         = $Concurrency
    timestamp_utc                       = $timestamp
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
