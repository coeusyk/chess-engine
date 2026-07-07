<#
.SYNOPSIS
    Per-group Texel tuning + 2-stage SPRT workflow.

.DESCRIPTION
    Runs the full Phase B tuning → apply → build → SPRT cycle for one or more
    parameter groups.  Groups are processed in order; each accepted group's JAR
    becomes the baseline for the next.

    Workflow per group:
      1. Save current engine-uci JAR as the baseline for this SPRT.
      2. Run tuner: --param-group <G> --freeze-k  (Phase B, uses K from tuned_params.txt).
      3. Apply tuned params to engine-core source files + sync EvalParams baseline.
      4. Rebuild engine-uci JAR.
      5a. Stage 1 (STC): SPRT new JAR vs baseline JAR at StcTC.  Prompt for verdict.
          H0 accepted → revert immediately.  H1 accepted → proceed to Stage 2 (if -TwoStage).
      5b. Stage 2 (LTC): SPRT at LtcTC.  H1 accepted → commit.  H0 accepted → revert.
      6a. Both stages H1 → commit changes; baseline = new JAR.
      6b. Any stage H0 → git checkout -- engine-core; restore EvalParams; baseline unchanged.

.PARAMETER Groups
    Comma-separated list of groups to tune in order.
    Default: scalars,material,mobility,king-safety,pawn-structure,pst

.PARAMETER MaxPositions
    Maximum corpus positions per tuning run.  Default: 0 (all = 725 000).

.PARAMETER MaxIterations
    Adam iterations per tuning run.  Default: 500.

.PARAMETER Elo1
    SPRT alternative hypothesis Elo bound (default 5 for tuner-methodology validations).

.PARAMETER TwoStage
    If set, runs a 2-stage SPRT: Stage 1 at StcTC then Stage 2 at LtcTC.
    Both stages must accept H1 for the group to be committed.
    If not set, runs a single SPRT at LtcTC only.

.PARAMETER StcTC
    Time control for Stage 1 (STC).  Default: '10+0.1'.

.PARAMETER LtcTC
    Time control for Stage 2 (LTC) or the single-stage run.  Default: '60+0.6'.

.PARAMETER TagPrefix
    Prefix for SPRT result tags (e.g. 'phase14').  Default: 'phase14'.

.PARAMETER SkipPhaseA
    If set, skips Phase A K calibration (uses K already in tuned_params.txt).

.EXAMPLE
    # 2-stage STC+LTC workflow for three groups
    .\tools\tune-groups.ps1 -Groups king-safety,mobility,pawn-structure -TwoStage -SkipPhaseA

.EXAMPLE
    # Single-stage LTC only (legacy behaviour)
    .\tools\tune-groups.ps1 -Groups scalars,material -Elo1 5

.EXAMPLE
    # Quick smoke-test: tune scalars only, 50k positions
    .\tools\tune-groups.ps1 -Groups scalars -MaxPositions 50000 -MaxIterations 200
#>
param(
    [string[]]$Groups      = @("scalars","material","mobility","king-safety","pawn-structure","pst"),
    [int]$MaxPositions     = 0,
    [int]$MaxIterations    = 500,
    [int]$Elo1             = 5,
    [switch]$TwoStage,
    [string]$StcTC         = "10+0.1",
    [string]$LtcTC         = "60+0.6",
    [string]$TagPrefix     = "phase14",
    [switch]$SkipPhaseA
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot   = Split-Path $PSScriptRoot -Parent
$engineRoot = $PSScriptRoot | Split-Path -Parent   # chess-engine/
$corpus     = Join-Path $engineRoot "tools\quiet-labeled.epd"
$tunerJar   = Join-Path $engineRoot "engine-tuner\target\engine-tuner-0.5.7-SNAPSHOT-shaded.jar"
$engineJar  = Join-Path $engineRoot "engine-uci\target\engine-uci-0.5.7-SNAPSHOT.jar"
$toolsDir   = Join-Path $engineRoot "tools"

$Java       = if ($env:JAVA) { $env:JAVA } else { 'java' }

# Verify prerequisites
if (-not (Test-Path $corpus))    { Write-Error "Corpus not found: $corpus"; exit 1 }
if (-not (Test-Path $tunerJar))  { Write-Error "Tuner JAR not found: $tunerJar - run: .\mvnw.cmd package -pl engine-tuner -am -DskipTests"; exit 1 }
if (-not (Test-Path $engineJar)) { Write-Error "Engine JAR not found: $engineJar - run: .\mvnw.cmd package -pl engine-uci -am -DskipTests"; exit 1 }

$posArg  = if ($MaxPositions -gt 0) { @("$MaxPositions", "$MaxIterations") } else { @("2147483646", "$MaxIterations") }
$groupList = $Groups

Write-Host "================================================================"
Write-Host " tune-groups.ps1"
Write-Host "  Groups    : $($groupList -join ', ')"
Write-Host "  Corpus    : $corpus"
Write-Host "  Positions : $(if ($MaxPositions -gt 0) { $MaxPositions } else { 'all' })"
Write-Host "  Iterations: $MaxIterations"
Write-Host "  Elo1 (SPRT): $Elo1"
Write-Host "  SPRT mode : $(if ($TwoStage) { "2-stage  STC=$StcTC → LTC=$LtcTC" } else { "single-stage  TC=$LtcTC" })"
Write-Host "  Tag prefix: $TagPrefix"
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

    # Step 5: SPRT — Stage 1 (STC) and optionally Stage 2 (LTC)
    $sprtScript = Join-Path $toolsDir "sprt.ps1"
    $verdict    = 'H0'

    if ($TwoStage) {
        # Stage 1: STC
        $stcTag = "${TagPrefix}-${group}-stc"
        Write-Host "[tune-groups] Stage 1 SPRT (STC $StcTC): -Tag '$stcTag' ..."
        & $sprtScript -New $engineJar -Old $baselineSnapshot -Tag $stcTag -Elo1 $Elo1 -TC $StcTC
        Write-Host ""
        $v1 = (Read-Host "[tune-groups] Stage 1 (STC) verdict: H1 (pass to LTC) / H0 (revert) / skip").ToUpper().Trim()

        if ($v1 -eq 'H1') {
            # Stage 2: LTC
            $ltcTag = "${TagPrefix}-${group}-ltc"
            Write-Host "[tune-groups] Stage 1 passed. Running Stage 2 SPRT (LTC $LtcTC): -Tag '$ltcTag' ..."
            & $sprtScript -New $engineJar -Old $baselineSnapshot -Tag $ltcTag -Elo1 $Elo1 -TC $LtcTC
            Write-Host ""
            $v2 = (Read-Host "[tune-groups] Stage 2 (LTC) verdict: H1 (commit) / H0 (revert)").ToUpper().Trim()
            $verdict = $v2
        } else {
            $verdict = $v1   # H0 or skip on STC
        }
    } else {
        # Single-stage: run at LtcTC
        $tag = "${TagPrefix}-${group}-group"
        Write-Host "[tune-groups] Running SPRT (single-stage $LtcTC): -Tag '$tag' (Elo1=$Elo1) ..."
        & $sprtScript -New $engineJar -Old $baselineSnapshot -Tag $tag -Elo1 $Elo1 -TC $LtcTC
        Write-Host ""
        $verdict = (Read-Host "[tune-groups] Enter SPRT verdict: H1 (keep) / H0 (revert) / skip").ToUpper().Trim()
    }

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
