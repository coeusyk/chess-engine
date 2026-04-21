<#
.SYNOPSIS
    Per-group Texel tuning + SPRT workflow.

.DESCRIPTION
    Runs the full Phase B tuning → apply → build → SPRT cycle for one or more
    parameter groups.  Groups are processed in order; each accepted group's JAR
    becomes the baseline for the next.

    Workflow per group:
      1. Save current engine-uci JAR as the baseline for this SPRT.
      2. Run tuner: --param-group <G> --freeze-k  (Phase B, uses K from tuned_params.txt).
      3. Apply tuned params to engine-core source files + sync EvalParams baseline.
      4. Rebuild engine-uci JAR.
      5. SPRT new JAR vs baseline JAR with -Tag "phase13-<G>-group".
      6a. H1 accepted → commit changes; baseline = new JAR.
      6b. H0 accepted → git checkout -- engine-core; restore EvalParams; baseline unchanged.

.PARAMETER Groups
    Comma-separated list of groups to tune in order.
    Default: scalars,material,mobility,king-safety,pawn-structure,pst

.PARAMETER MaxPositions
    Maximum corpus positions per tuning run.  Default: 0 (all = 725 000).

.PARAMETER MaxIterations
    Adam iterations per tuning run.  Default: 500.

.PARAMETER Elo1
    SPRT alternative hypothesis Elo bound (default 5 for tuner-methodology validations).

.PARAMETER SkipPhaseA
    If set, skips Phase A K calibration (uses K already in tuned_params.txt).

.EXAMPLE
    # Full per-group workflow with tight SPRT (Elo1=5)
    .\tools\tune-groups.ps1 -Groups scalars,material,mobility,king-safety,pawn-structure -Elo1 5

.EXAMPLE
    # Quick smoke-test: tune scalars only, 50k positions
    .\tools\tune-groups.ps1 -Groups scalars -MaxPositions 50000 -MaxIterations 200
#>
param(
    [string]$Groups        = "scalars,material,mobility,king-safety,pawn-structure,pst",
    [int]$MaxPositions     = 0,
    [int]$MaxIterations    = 500,
    [int]$Elo1             = 5,
    [switch]$SkipPhaseA
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot   = Split-Path $PSScriptRoot -Parent
$engineRoot = $PSScriptRoot | Split-Path -Parent   # chess-engine/
$corpus     = Join-Path $engineRoot "tools\quiet-labeled.epd"
$tunerJar   = Join-Path $engineRoot "engine-tuner\target\engine-tuner-0.5.7-SNAPSHOT-shaded.jar"
$engineJar  = Join-Path $engineRoot "engine-uci\target\engine-uci-0.5.7-SNAPSHOT-shaded.jar"
$toolsDir   = Join-Path $engineRoot "tools"

$Java       = if ($env:JAVA) { $env:JAVA } else { 'java' }

# Verify prerequisites
if (-not (Test-Path $corpus))    { Write-Error "Corpus not found: $corpus"; exit 1 }
if (-not (Test-Path $tunerJar))  { Write-Error "Tuner JAR not found: $tunerJar - run: .\mvnw.cmd package -pl engine-tuner -am -DskipTests"; exit 1 }
if (-not (Test-Path $engineJar)) { Write-Error "Engine JAR not found: $engineJar - run: .\mvnw.cmd package -pl engine-uci -am -DskipTests"; exit 1 }

$posArg  = if ($MaxPositions -gt 0) { @("$MaxPositions", "$MaxIterations") } else { @("2147483647", "$MaxIterations") }
$groupList = $Groups -split ','

Write-Host "================================================================"
Write-Host " tune-groups.ps1"
Write-Host "  Groups    : $($groupList -join ', ')"
Write-Host "  Corpus    : $corpus"
Write-Host "  Positions : $(if ($MaxPositions -gt 0) { $MaxPositions } else { 'all' })"
Write-Host "  Iterations: $MaxIterations"
Write-Host "  Elo1 (SPRT): $Elo1"
Write-Host "================================================================"
Write-Host ""

# --- Phase A: K calibration (run once before all groups) -------------------
if (-not $SkipPhaseA) {
    Write-Host ">>> Phase A: K calibration (--freeze-params) ..."
    Push-Location $engineRoot
    & $Java -jar $tunerJar $corpus @posArg `
        --corpus-format epd --freeze-params
    Pop-Location
    Write-Host ">>> Phase A complete.  K written to tuned_params.txt."
    Write-Host ""
} else {
    Write-Host ">>> Skipping Phase A (--SkipPhaseA).  Using K from tuned_params.txt."
    Write-Host ""
}

# --- Per-group loop ---------------------------------------------------------
$baselineJar = $engineJar   # starts as current HEAD JAR
$iteration   = 0

foreach ($group in $groupList) {
    $iteration++
    Write-Host "================================================================"
    Write-Host " GROUP $iteration/$($groupList.Count): $group"
    Write-Host "================================================================"

    # Step 1: Save current JAR as SPRT baseline
    $baselineSnapshot = Join-Path $toolsDir "baseline-before-${group}.jar"
    Copy-Item $engineJar $baselineSnapshot -Force
    Write-Host "[tune-groups] Baseline snapshot: $baselineSnapshot"

    # Step 2: Phase B - tune this group only with fixed K
    Write-Host "[tune-groups] Phase B: tuning group '$group' ..."
    Push-Location $engineRoot
    & $Java -jar $tunerJar $corpus @posArg `
        --corpus-format epd --param-group $group --freeze-k
    $tunerExit = $LASTEXITCODE
    Pop-Location

    if ($tunerExit -ne 0) {
        Write-Error "[tune-groups] Tuner exited with code $tunerExit for group '$group'. Skipping."
        continue
    }
    Write-Host "[tune-groups] Phase B complete. tuned_params.txt updated."

    # Step 3: Apply tuned params for this group to engine-core + EvalParams
    Write-Host "[tune-groups] Applying params for group '$group' ..."
    & (Join-Path $toolsDir "apply-tuned-params.ps1") -Group $group

    # Step 4: Rebuild engine-uci JAR
    Write-Host "[tune-groups] Rebuilding engine-uci JAR ..."
    Push-Location $engineRoot
    & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
    $buildExit = $LASTEXITCODE
    Pop-Location

    if ($buildExit -ne 0) {
        Write-Error "[tune-groups] Maven build failed for group '$group'. Reverting source files."
        Push-Location $engineRoot
        & git checkout -- engine-core/src
        & git checkout -- engine-tuner/src/main/java/coeusyk/game/chess/tuner/EvalParams.java
        Pop-Location
        continue
    }

    # Step 5: SPRT new JAR vs baseline
    $tag = "phase13-${group}-group"
    Write-Host "[tune-groups] Running SPRT: -Tag '$tag' (Elo1=$Elo1) ..."
    $sprtScript = Join-Path $toolsDir "sprt.ps1"
    & $sprtScript -New $engineJar -Old $baselineSnapshot -Tag $tag -Elo1 $Elo1

    # Step 6: prompt for result
    Write-Host ""
    $verdict = Read-Host "[tune-groups] Enter SPRT verdict: H1 (keep) / H0 (revert) / skip"
    $verdict = $verdict.ToUpper().Trim()

    if ($verdict -eq 'H1') {
        # Commit the accepted group
        Push-Location $engineRoot
        & git add engine-core/src engine-tuner/src/main/java/coeusyk/game/chess/tuner/EvalParams.java tuned_params.txt
        & git commit -m "feat(eval): Texel tuning - $group group (phase14 per-group SPRT H1)"
        Pop-Location
        Write-Host "[tune-groups] OK Group '$group' committed.  Baseline updated."
        # engineJar already points to newly built JAR -> becomes baseline for next group
    } elseif ($verdict -eq 'H0') {
        Write-Host "[tune-groups] REVERTED H0 for group '$group'. Reverting source files."
        Push-Location $engineRoot
        & git checkout -- engine-core/src
        & git checkout -- engine-tuner/src/main/java/coeusyk/game/chess/tuner/EvalParams.java
        & git checkout -- tuned_params.txt
        # Restore the pre-group JAR so the engine is back to baseline
        Copy-Item $baselineSnapshot $engineJar -Force
        Pop-Location
        Write-Host "[tune-groups] Source files reverted. Engine JAR restored to baseline."
    } else {
        Write-Host "[tune-groups] Skipped manual verdict for group '$group'. No changes committed."
    }
    Write-Host ""
}

Write-Host "================================================================"
Write-Host " tune-groups.ps1 complete."
Write-Host "================================================================"
