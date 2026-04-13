<#
.SYNOPSIS
    B-3: Mobility Texel tuning + SPRT.

.DESCRIPTION
    Step 1 — Rebuild engine-tuner with current eval constants (--param-group mobility).
    Step 2 — Run Texel/Adam tuning on data/quiet-labeled.epd (150 iterations, full corpus).
    Step 3 — Validate: no negative EG mobility values in tuned_params.txt.
    Step 4 — Apply tuned_params.txt to Evaluator.java and EvalParams.java.
    Step 5 — Build tuned engine-uci JAR.
    Step 6 — SPRT vs. a pre-tuning baseline JAR:
                H0=0 Elo, H1=15 Elo, α=β=0.05
                TC: 60+0.6 — 6 concurrent games, 2 threads/engine, min 800 games.

    If SPRT accepts H0, source files are restored via git checkout.

    Run from the chess-engine/ directory:
        .\tools\run_b3_sprt.ps1

.PARAMETER SkipTune
    Skip tuner run; reuse existing tuned_params.txt and tuned JAR.

.PARAMETER SkipBuild
    Skip all Maven builds; assume all JARs already present in tools/.
#>
param(
    [switch]$SkipTune,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ─── Paths ───────────────────────────────────────────────────────────────────
$VersionSuffix   = "0.5.6-SNAPSHOT"
$TunerJar        = "engine-tuner\target\engine-tuner-${VersionSuffix}-shaded.jar"
$CorpusFile      = "data\quiet-labeled.epd"
$TunedParamsFile = "tuned_params.txt"
$BuiltEngineJar  = "engine-uci\target\engine-uci-${VersionSuffix}.jar"
$BaselineJar     = "tools\engine-uci-b3-baseline.jar"
$TunedJar        = "tools\engine-uci-b3-mobility-tuned.jar"

# Source files modified by group=mobility
$EvaluatorFile   = "engine-core\src\main\java\coeusyk\game\chess\core\eval\Evaluator.java"
$EvalParamsFile  = "engine-tuner\src\main\java\coeusyk\game\chess\tuner\EvalParams.java"

if (-not (Test-Path $CorpusFile)) {
    Write-Error "Corpus file not found: $CorpusFile. Run from chess-engine/ directory."
    exit 1
}

Write-Host "B-3 Experiment — Mobility Texel Tuning + SPRT"
Write-Host "------------------------------------------------"
Write-Host "Corpus       : $CorpusFile (full, ~703k positions)"
Write-Host "Iterations   : 150 (Adam optimizer, freeze-k)"
Write-Host "SPRT         : H0=0 / H1=15 Elo, alpha=beta=0.05"
Write-Host "TC           : 60+0.6  |  6 concurrent  |  2 threads/engine  |  min 800 games"
Write-Host ""

# ─── Step 1: Build baseline JAR (current source, before tuning) ──────────────
if (-not $SkipBuild) {
    Write-Host "[B-3] Building baseline JAR from current HEAD..."
    & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Error "Maven build (baseline) failed"; exit 1 }
    if (-not (Test-Path $BuiltEngineJar)) { Write-Error "Baseline engine JAR not found: $BuiltEngineJar"; exit 1 }
    Copy-Item $BuiltEngineJar $BaselineJar -Force
    Write-Host "[B-3] Baseline JAR saved: $BaselineJar"

    Write-Host "[B-3] Building engine-tuner..."
    & .\mvnw.cmd package -pl engine-tuner -am -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Error "Maven build (tuner) failed"; exit 1 }
    Write-Host "[B-3] Tuner JAR: $TunerJar"
} else {
    Write-Host "[B-3] SkipBuild — assuming $BaselineJar and $TunerJar already exist."
    if (-not (Test-Path $BaselineJar)) { Write-Error "Baseline JAR not found (SkipBuild): $BaselineJar"; exit 1 }
    if (-not (Test-Path $TunerJar))    { Write-Error "Tuner JAR not found (SkipBuild): $TunerJar"; exit 1 }
}

# ─── Step 2: Run Texel tuning (150 iterations, full corpus) ──────────────────
if (-not $SkipTune) {
    Write-Host ""
    Write-Host "[B-3] Running Texel tuning (mobility, 150 iterations, full corpus)..."
    Write-Host "[B-3] NOTE: Full corpus tuning takes significantly longer than seed-based runs."
    Write-Host ""
    & java -jar $TunerJar $CorpusFile 5000000 150 --optimizer adam --param-group mobility --freeze-k
    if ($LASTEXITCODE -ne 0) { Write-Error "Tuner exited with error $LASTEXITCODE"; exit 1 }
    Write-Host ""
    Write-Host "[B-3] Tuning complete. Reading $TunedParamsFile..."
    Get-Content $TunedParamsFile | Select-String -Pattern 'MOBILITY|MOB'
} else {
    Write-Host "[B-3] SkipTune — reusing existing $TunedParamsFile"
    if (-not (Test-Path $TunedParamsFile)) { Write-Error "tuned_params.txt not found (SkipTune)"; exit 1 }
}

# ─── Step 3: Validate: no negative EG mobility values ────────────────────────
Write-Host ""
Write-Host "[B-3] Validating tuned mobility values..."
$paramContent = Get-Content $TunedParamsFile -Raw

# Parse mobility EG values; each looks like: MOBILITY_EG_KNIGHT=N, etc.
$egPattern = 'MOBILITY_EG_[A-Z]+\s*=\s*(-?\d+)'
$matches = [regex]::Matches($paramContent, $egPattern)
$negatives = @()
foreach ($m in $matches) {
    $val = [int]$m.Groups[1].Value
    if ($val -lt 0) {
        $negatives += "$($m.Value)"
    }
}

if ($negatives.Count -gt 0) {
    Write-Warning "[B-3] VALIDATION FAILED: Negative EG mobility values found:"
    $negatives | ForEach-Object { Write-Warning "  $_" }
    Write-Warning "[B-3] Aborting — tune did not converge correctly. Tune again or inspect manually."
    exit 1
} else {
    Write-Host "[B-3] Validation passed: all EG mobility values are non-negative."
}

# ─── Step 4: Apply tuned params ──────────────────────────────────────────────
Write-Host ""
Write-Host "[B-3] Applying tuned params (mobility group)..."
& .\tools\apply-tuned-params.ps1 -Group mobility -TunedParamsFile $TunedParamsFile
if ($LASTEXITCODE -ne 0) { Write-Error "apply-tuned-params failed"; exit 1 }

# ─── Step 5: Build tuned engine JAR ──────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host ""
    Write-Host "[B-3] Building tuned engine JAR..."
    & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Tuned build failed — restoring source files..."
        git checkout -- $EvaluatorFile $EvalParamsFile
        exit 1
    }
    if (-not (Test-Path $BuiltEngineJar)) { Write-Error "Tuned engine JAR not found: $BuiltEngineJar"; exit 1 }
    Copy-Item $BuiltEngineJar $TunedJar -Force
    Write-Host "[B-3] Tuned JAR saved: $TunedJar"
} else {
    Write-Host "[B-3] SkipBuild — assuming $TunedJar already exists."
    if (-not (Test-Path $TunedJar)) { Write-Error "Tuned JAR not found (SkipBuild): $TunedJar"; exit 1 }
}

# ─── Step 6: Run SPRT ────────────────────────────────────────────────────────
Write-Host ""
Write-Host "[B-3] Running SPRT: tuned mobility vs baseline..."
try {
    & .\tools\sprt.ps1 `
        -New $TunedJar `
        -Old $BaselineJar `
        -Tag "phase13-b3-mobility-tuned" `
        -BonferroniM 1 `
        -Elo0 0 -Elo1 15 `
        -TC '60+0.6' `
        -Concurrency 6 `
        -EngineThreads 2 `
        -MinGames 800

    Write-Host ""
    Write-Host "[B-3] SPRT complete."
    Write-Host "[B-3] Review the result above."
    Write-Host "[B-3] If H1 accepted  → commit: git add $EvaluatorFile $EvalParamsFile && git commit"
    Write-Host "[B-3] If H0 accepted  → restore: git checkout -- $EvaluatorFile $EvalParamsFile"
} catch {
    Write-Warning "B-3 SPRT failed: $_"
    Write-Host "[B-3] Restoring source files due to error..."
    git checkout -- $EvaluatorFile $EvalParamsFile
    exit 1
}
