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

    Both engines under test, candidate and baseline, are built from exact, frozen
    commits via disposable detached git worktrees. Neither is ever built from whatever
    happens to be checked out in the directory this script runs from. That checkout (the
    "orchestration checkout") supplies only this script itself, the preregistration
    documents, and the location evidence gets written to; its HEAD may legitimately carry
    later documentation/tooling commits without changing either engine under test, and
    this script does not try to identify or diff its production source at all. Building
    each engine from its own frozen commit, rather than diffing a moving checkout's
    src/main against a frozen commit, closes a gap the previous version of this script
    had: a src/main-only diff cannot see a change to pom.xml, a build-plugin
    configuration, the shade configuration, a compiler setting, or any other build input
    outside src/main that could still produce a different JAR. Building from the exact
    commit makes that class of gap structurally impossible rather than merely checked
    for. This also means the prior "does Searcher.java contain the repaired expression"
    grep and the prior "diff src/main against f9b152c" check are both removed: they were
    partial substitutes for exact-commit builds, and once both engines are actually built
    from exact, git-rev-parse-verified commits, keeping a second, weaker identity
    mechanism alongside the strong one would only risk the two disagreeing later, not add
    real assurance.

    Candidate: commit f9b152ca4f45e8e8aa5a48092b03416aba79b230 (the isPvNode-propagation
    repair). Built via a disposable detached worktree, exactly like the baseline; the
    orchestration checkout's own HEAD is never built.

    Baseline: commit ebe513eabd50e853a4e24a0260c64b41a5a4b224 (the develop merge commit
    Phase 17 itself branched from, and the same pre-PVS source that produced every
    "pre-PVS baseline" figure in this phase's Gate 1/Gate 2 evidence; see
    dev-entries/phase-17.md and
    docs/architecture/research/phase16-p16-3-intervention-preregistration.md section 8,
    "one isolated, same-baseline SPRT against the current Threads=1 build").

    Both commit SHAs are frozen internal constants, not command-line parameters. Before
    building either engine, the script resolves the ref with `git rev-parse` and refuses
    to proceed unless it resolves to exactly the full frozen SHA; after `git worktree add
    --detach`, it independently re-checks the worktree's own HEAD against that same SHA.
    A different candidate or baseline commit requires a preregistration amendment and a
    code change to this script before game 1, exactly like the concurrency and
    cutechess-cli version amendments recorded in the preregistration document's amendment
    log.

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
      Concurrency     6               (amended 2 -> 6 before game 1; target host: AMD
                                        Ryzen 7 7700X, 8 physical cores / 16 logical
                                        threads. Each engine instance is Threads=1, so 6
                                        simultaneous games is an operational capacity
                                        choice, not a claim that one game maps exactly to
                                        one physical core, since engine processes, OS
                                        scheduling, SMT, and the idle side of each game
                                        all complicate that mapping. 6 stays below the 8
                                        physical cores, leaving roughly two physical
                                        cores of scheduling headroom for Windows,
                                        JVM/process overhead, cutechess-cli itself, and
                                        interactive desktop use during a potentially long
                                        run, deliberately not attempting to saturate all
                                        16 logical/SMT threads. It does not change the
                                        mathematical elo0/elo1/alpha/beta SPRT bounds,
                                        but it is still part of the experimental
                                        conditions, not a free knob: at a wall-clock TC
                                        like 5+0.05, CPU contention under a given
                                        concurrency can change effective compute
                                        available per move, which can affect observed
                                        game outcomes, particularly since the candidate
                                        and baseline are expected to have different
                                        search-efficiency characteristics. It is frozen
                                        the same as every other term below precisely
                                        because of that: fixed before game 1, not
                                        overridable at the command line, not changeable
                                        mid-run.)
      cutechess-cli   1.5.1 (amended from the originally-frozen v1.4.0 before game 1;
                                        verified against the installed binary before any
                                        game runs, see the version-check section below)

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

# Frozen protocol constants. Not parameters: changing any of these is a new experiment.
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

# Frozen candidate: the isPvNode-propagation repair commit, built from an exact worktree,
# never from the orchestration checkout's own (possibly later) HEAD.
$CandidateRef = "f9b152ca4f45e8e8aa5a48092b03416aba79b230"

# Frozen baseline: the develop merge commit this phase branched from, also built from an
# exact worktree.
$BaselineRef = "ebe513eabd50e853a4e24a0260c64b41a5a4b224"

# Frozen cutechess-cli version. Amended from the originally-frozen v1.4.0 to the latest
# release (checked 2026-09-20 via `gh api repos/cutechess/cutechess/releases/latest`); the
# v1.4.0 -> v1.5.1 changelog contains only bug fixes and a Qt 5 -> Qt 6 build-tooling change,
# nothing affecting SPRT/game-management logic for a standard-variant UCI match, so there is
# no reproducibility reason to stay pinned to the older release.
$CutechessVersion = "1.5.1"

function Write-Section($title) {
    Write-Host ""
    Write-Host "==== $title ====" -ForegroundColor Cyan
}

# Builds one engine JAR from an exact, verified commit, via a disposable detached worktree.
# Used identically for both the candidate and the baseline, so there is exactly one code
# path that can build an experimental JAR, not two that could quietly drift apart.
function Build-FrozenEngineJar {
    param(
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$FrozenSha,
        [Parameter(Mandatory)][string]$OutDir,
        [Parameter(Mandatory)][string]$BuildLogName,
        [Parameter(Mandatory)][string]$WorktreeAddLogName
    )

    Write-Section "Resolve and build $Label ($FrozenSha, disposable worktree)"

    $resolved = (git rev-parse $FrozenSha 2>&1)
    if ($LASTEXITCODE -ne 0 -or $resolved.Trim() -ne $FrozenSha) {
        Write-Host "REFUSING: $Label ref $FrozenSha did not resolve to itself as a full SHA." -ForegroundColor Red
        Write-Host "git rev-parse output: $resolved" -ForegroundColor Red
        Write-Host "The frozen $Label commit is not present in this checkout's history (fetch it" -ForegroundColor Red
        Write-Host "first) or the frozen constant no longer names a real commit." -ForegroundColor Red
        exit 1
    }
    Write-Host "$Label ref resolved and verified: $resolved"

    $worktreeDir = Join-Path $env:TEMP "p17-4-$Label-$timestamp"
    git worktree add --detach $worktreeDir $FrozenSha 2>&1 |
        Tee-Object -FilePath (Join-Path $OutDir $WorktreeAddLogName)
    if ($LASTEXITCODE -ne 0) {
        Write-Host "git worktree add failed for $Label, see $WorktreeAddLogName. Stopping." -ForegroundColor Red
        exit 1
    }

    try {
        $actualSha = (git -C $worktreeDir rev-parse HEAD).Trim()
        if ($actualSha -ne $FrozenSha) {
            # Should be unreachable given `git worktree add --detach $FrozenSha` above, but
            # checked independently anyway: this is the one fact this whole script exists
            # to guarantee, so it is never assumed from how the worktree was created.
            Write-Host "REFUSING: $Label worktree's actual HEAD ($actualSha) does not match the frozen ref ($FrozenSha)." -ForegroundColor Red
            exit 1
        }
        Write-Host "$Label worktree HEAD confirmed: $actualSha"

        Push-Location $worktreeDir
        try {
            mvn -pl engine-core,engine-uci -am package -DskipTests 2>&1 |
                Tee-Object -FilePath (Join-Path $OutDir $BuildLogName)
            if ($LASTEXITCODE -ne 0) {
                Write-Host "$Label build failed, see $BuildLogName. Stopping." -ForegroundColor Red
                exit 1
            }
            $jarSrc = Get-ChildItem -Path "engine-uci\target" -Filter "engine-uci-*-SNAPSHOT.jar" |
                Where-Object { $_.Name -notlike "original-*" } | Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
            if (-not $jarSrc) {
                Write-Host "Could not find the $Label JAR under $worktreeDir\engine-uci\target." -ForegroundColor Red
                exit 1
            }
        } finally {
            Pop-Location
        }

        $jarPath = Join-Path $OutDir "$Label-$actualSha.jar"
        Copy-Item -Path $jarSrc.FullName -Destination $jarPath -Force
        $jarSha256 = (Get-FileHash -Path $jarPath -Algorithm SHA256).Hash
        Write-Host "$Label JAR: $jarPath"
        Write-Host "SHA-256:  $jarSha256"

        return [ordered]@{
            FrozenSha = $FrozenSha
            ActualSha = $actualSha
            JarPath   = $jarPath
            JarSha256 = $jarSha256
        }
    } finally {
        git worktree remove --force $worktreeDir 2>&1 | Out-Null
    }
}

# ---------------------------------------------------------------------------
# 0. Refuse anything but an explicit, deliberate invocation.
# ---------------------------------------------------------------------------
Write-Section "Phase 17 Step 4: frozen SPRT protocol (see this file's header comment)"
Write-Host "Candidate: exact frozen commit $CandidateRef (disposable worktree, never the orchestration checkout's HEAD)"
Write-Host "Baseline:  exact frozen commit $BaselineRef (disposable worktree)"
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
# 2. Orchestration checkout state. This checkout supplies this script, the
#    preregistration documents, and the evidence output location only. It is never
#    built as either engine under test, so its production source is not diffed or
#    otherwise checked for candidate/baseline identity here.
# ---------------------------------------------------------------------------
Write-Section "Orchestration checkout state (not an engine under test)"
$orchestrationSha = (git rev-parse HEAD).Trim()
$orchestrationBranch = (git rev-parse --abbrev-ref HEAD).Trim()
# Tracked state only: modified or staged tracked files would mean this script itself, the
# preregistration documents, or some other tracked file differs from what was reviewed and
# committed, which is worth refusing on. Untracked files are not: this checkout has a known,
# pre-existing untracked directory (.claude/agent-memory/) that is unrelated to either engine
# under test, and this checkout's production source is never built or diffed here anyway
# (section header above), so an untracked file here cannot silently change either engine.
$trackedStatus = git status --porcelain --untracked-files=no
$fullStatus = git status --porcelain
Write-Host "Branch: $orchestrationBranch"
Write-Host "HEAD:   $orchestrationSha"
if ($trackedStatus) {
    Write-Host "Tracked orchestration state is dirty:" -ForegroundColor Red
    Write-Host $trackedStatus
    Write-Host "Refusing to run with modified or staged tracked files. Commit or stash first." -ForegroundColor Red
    exit 1
}
Write-Host "Tracked state clean. This HEAD may legitimately be later than either frozen engine commit (docs/tooling commits are expected)."
if ($fullStatus) {
    Write-Host "Untracked paths present (allowed, recorded as evidence only, not built or diffed):"
    Write-Host $fullStatus
}

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
"orchestration_branch=$orchestrationBranch`norchestration_head=$orchestrationSha`ncandidate_frozen_commit=$CandidateRef`nbaseline_frozen_commit=$BaselineRef" |
    Out-File -FilePath (Join-Path $outDir "01-git-state.txt") -Encoding utf8
# Untracked paths (e.g. .claude/agent-memory/) are allowed and recorded here for auditability
# only; they are never hashed, inspected, or treated as part of either engine's source.
if ($fullStatus) {
    $fullStatus | Out-File -FilePath (Join-Path $outDir "01b-orchestration-untracked.txt") -Encoding utf8
} else {
    "(no untracked or modified paths)" | Out-File -FilePath (Join-Path $outDir "01b-orchestration-untracked.txt") -Encoding utf8
}

# ---------------------------------------------------------------------------
# 4. Build the candidate JAR from its exact frozen commit.
# ---------------------------------------------------------------------------
$candidateBuild = Build-FrozenEngineJar `
    -Label "candidate" `
    -FrozenSha $CandidateRef `
    -OutDir $outDir `
    -BuildLogName "02-build-candidate.log" `
    -WorktreeAddLogName "02a-worktree-add-candidate.log"
$candidateJarPath = $candidateBuild.JarPath
$candidateJarSha256 = $candidateBuild.JarSha256
"candidate_actual_commit=$($candidateBuild.ActualSha)`ncandidate_jar_sha256=$candidateJarSha256" |
    Out-File -FilePath (Join-Path $outDir "01-git-state.txt") -Append -Encoding utf8

# ---------------------------------------------------------------------------
# 5. Build the baseline JAR from its exact frozen commit.
# ---------------------------------------------------------------------------
$baselineBuild = Build-FrozenEngineJar `
    -Label "baseline" `
    -FrozenSha $BaselineRef `
    -OutDir $outDir `
    -BuildLogName "03-build-baseline.log" `
    -WorktreeAddLogName "03a-worktree-add-baseline.log"
$baselineJarPath = $baselineBuild.JarPath
$baselineJarSha256 = $baselineBuild.JarSha256
"baseline_actual_commit=$($baselineBuild.ActualSha)`nbaseline_jar_sha256=$baselineJarSha256" |
    Out-File -FilePath (Join-Path $outDir "01-git-state.txt") -Append -Encoding utf8

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
# 6b. Environment record (mirrors tools/p17-2-native-throughput.ps1). Field names make
#     the orchestration/candidate/baseline relationship explicit and unambiguous: the
#     orchestration_* fields describe the checkout this script ran from; candidate_* and
#     baseline_* describe the two chess engines actually being compared, each built from
#     its own exact commit regardless of what orchestration_head happens to be.
# ---------------------------------------------------------------------------
Write-Section "Environment"
$javaVersionOutput = & java -version 2>&1 | Out-String
Write-Host $javaVersionOutput
$cpu = Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1
$os  = Get-CimInstance -ClassName Win32_OperatingSystem
$env_record = [ordered]@{
    orchestration_head          = $orchestrationSha
    orchestration_branch        = $orchestrationBranch
    candidate_frozen_commit     = $CandidateRef
    candidate_actual_commit     = $candidateBuild.ActualSha
    candidate_jar_sha256        = $candidateJarSha256
    baseline_frozen_commit      = $BaselineRef
    baseline_actual_commit      = $baselineBuild.ActualSha
    baseline_jar_sha256         = $baselineJarSha256
    openings_file               = $OpeningsFile
    openings_sha256             = $actualOpeningsSha256
    cutechess_path              = $cutechessPath
    cutechess_version_expected  = $CutechessVersion
    cutechess_version_output    = $cutechessVersionOutput
    cutechess_version_verified  = $cutechessVersionVerified
    java_version_raw            = $javaVersionOutput.Trim()
    os_caption                  = $os.Caption
    cpu_name                    = $cpu.Name
    tc                          = $TC
    elo0                        = $Elo0
    elo1                        = $Elo1
    alpha                       = $Alpha
    beta                        = $Beta
    threads_per_engine          = $EngineThreads
    hash_mb_per_engine          = $HashMb
    max_games                   = $MaxGames
    concurrency                 = $Concurrency
    timestamp_utc               = $timestamp
}
$env_record | ConvertTo-Json | Out-File -FilePath (Join-Path $outDir "05-environment.json") -Encoding utf8
Write-Host "IMPORTANT: verify java_version_raw above is the JDK 21 toolchain this project expects." -ForegroundColor Yellow

# ---------------------------------------------------------------------------
# 7. Hand off to the existing, already-tested match runner with frozen terms.
#    This is the only step that spends games. Everything above is prep/evidence.
#
#    tools/sprt.ps1 accepts no output-path parameter: it always generates its own
#    log/pgn filenames from -Tag and a Get-Date timestamp captured when IT starts
#    (not this script's own $timestamp), under tools/results/ directly, never under
#    this run's own $outDir. Two things follow from that:
#      - "2>&1 | Tee-Object" alone does not reliably capture sprt.ps1's own console
#        messaging, since nearly all of it goes through Write-Host, which writes to
#        PowerShell's information stream (6), not the error stream (2) that 2>&1
#        merges. "*>&1" (all streams) is required to route Write-Host output into
#        this pipe at all.
#      - Even with *>&1 fixed, the transcript this produces is a secondary copy of
#        console output, not the authoritative record. tools/sprt.ps1's own log/pgn
#        (written via its internal StreamWriter and cutechess-cli's -pgnout) are the
#        authoritative artifacts, and this script locates and copies them into its
#        own evidence directory rather than assuming a path it invented.
# ---------------------------------------------------------------------------
Write-Section "Invoking tools\sprt.ps1 with frozen Phase 17 Step 4 terms"
$sprtLog = Join-Path $outDir "06-sprt-console.log"
$sprtResultsDir = Join-Path $repoRoot "tools\results"
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
    *>&1 | Tee-Object -FilePath $sprtLog

# Locate the authoritative log/pgn tools/sprt.ps1 itself wrote for this call: the newest
# files matching this run's -Tag under tools/results/. This does not invoke cutechess or
# sprt.ps1 again; it only looks at what the call above already produced.
$sprtAuthoritativeLog = Get-ChildItem -Path $sprtResultsDir -Filter "sprt_phase17-pvs_*.log" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
$sprtAuthoritativePgn = Get-ChildItem -Path $sprtResultsDir -Filter "sprt_phase17-pvs_*.pgn" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($sprtAuthoritativeLog) {
    Copy-Item -Path $sprtAuthoritativeLog.FullName -Destination (Join-Path $outDir "06a-sprt-authoritative.log") -Force
}
if ($sprtAuthoritativePgn) {
    Copy-Item -Path $sprtAuthoritativePgn.FullName -Destination (Join-Path $outDir "06b-sprt-authoritative.pgn") -Force
}

# Read the verdict from the authoritative log when it was found; fall back to the console
# transcript only if tools/sprt.ps1's own log could not be located (it should always be
# found in practice, since -pgnout/its internal log are unconditional).
$sprtOutputText = if ($sprtAuthoritativeLog) {
    Get-Content -Path $sprtAuthoritativeLog.FullName -Raw
} else {
    Get-Content -Path $sprtLog -Raw
}
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
