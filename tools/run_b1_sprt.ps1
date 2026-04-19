<#
.SYNOPSIS
    B-1: King-Safety Texel tuning + SPRT.

.DESCRIPTION
    Step 1 — Rebuild engine-tuner with current eval constants (--param-group king-safety).
    Step 2 — Run Texel/Adam tuning on king_safety_seeds.epd (200 iterations).
    Step 3 — Apply tuned_params.txt to KingSafety.java and EvalParams.java.
    Step 4 — Build engine-uci JAR with the tuned eval.
    Step 5 — SPRT vs. a pre-tuning baseline JAR:
                H0=0 Elo, H1=15 Elo, α=β=0.05
                TC: 60+0.6 — 6 concurrent games, 2 threads/engine, min 800 games.

    If SPRT accepts H1 the changed source files remain committed (caller's responsibility).
    If SPRT accepts H0 the source files are restored via git checkout.

    Run from the chess-engine/ directory:
        .\tools\run_b1_sprt.ps1

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
$SeedFile        = "tools\seeds\king_safety_seeds.epd"
$TunedParamsFile = "tuned_params.txt"
$BuiltEngineJar  = "engine-uci\target\engine-uci-${VersionSuffix}.jar"
$BaselineJar     = "tools\engine-uci-b1-baseline.jar"
$TunedJar        = "tools\engine-uci-b1-kingsafety-tuned.jar"

# Source files modified by apply-tuned-params for king-safety
$KingSafetyFile  = "engine-core\src\main\java\coeusyk\game\chess\core\eval\KingSafety.java"
$EvalParamsFile  = "engine-tuner\src\main\java\coeusyk\game\chess\tuner\EvalParams.java"

if (-not (Test-Path $SeedFile)) { Write-Error "Required file not found: $SeedFile"; exit 1 }

Write-Host "B-1 Experiment — King-Safety Texel Tuning + SPRT"
Write-Host "--------------------------------------------------"
Write-Host "Seed corpus  : $SeedFile"
Write-Host "Iterations   : 200 (Adam optimizer, freeze-k)"
Write-Host "SPRT         : H0=0 / H1=15 Elo, alpha=beta=0.05"
Write-Host "TC           : 60+0.6  |  6 concurrent  |  2 threads/engine  |  min 800 games"
Write-Host ""

# ─── Step 1: Build baseline JAR (current source, before tuning) ──────────────
if (-not $SkipBuild) {
    Write-Host "[B-1] Building baseline JAR from current HEAD..."
    & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Error "Maven build (baseline) failed"; exit 1 }
    if (-not (Test-Path $BuiltEngineJar)) { Write-Error "Baseline engine JAR not found: $BuiltEngineJar"; exit 1 }
    Copy-Item $BuiltEngineJar $BaselineJar -Force
    Write-Host "[B-1] Baseline JAR saved: $BaselineJar"

    # Rebuild tuner so it knows the current eval baselines
    Write-Host "[B-1] Building engine-tuner..."
    & .\mvnw.cmd package -pl engine-tuner -am -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Error "Maven build (tuner) failed"; exit 1 }
    Write-Host "[B-1] Tuner JAR: $TunerJar"
} else {
    Write-Host "[B-1] SkipBuild — assuming $BaselineJar and $TunerJar already exist."
    if (-not (Test-Path $BaselineJar)) { Write-Error "Baseline JAR not found (SkipBuild): $BaselineJar"; exit 1 }
    if (-not (Test-Path $TunerJar))    { Write-Error "Tuner JAR not found (SkipBuild): $TunerJar"; exit 1 }
}

# ─── Step 2: Run Texel tuning ────────────────────────────────────────────────
if (-not $SkipTune) {
    Write-Host ""
    Write-Host "[B-1] Running Texel tuning (king-safety, 200 iterations)..."
    Write-Host "[B-1] This may take some time. Output below:"
    Write-Host ""
    & java -jar $TunerJar $SeedFile 5000000 200 --optimizer adam --param-group king-safety --freeze-k
    if ($LASTEXITCODE -ne 0) { Write-Error "Tuner exited with error $LASTEXITCODE"; exit 1 }
    Write-Host ""
    Write-Host "[B-1] Tuning complete. Reading $TunedParamsFile..."
    Get-Content $TunedParamsFile | Select-String -Pattern 'KING|SHIELD|PAWN|ATTACK|OPEN|HALF'
} else {
    Write-Host "[B-1] SkipTune — reusing existing $TunedParamsFile"
    if (-not (Test-Path $TunedParamsFile)) { Write-Error "tuned_params.txt not found (SkipTune)"; exit 1 }
}

# ─── Step 3: Apply tuned params ──────────────────────────────────────────────
Write-Host ""
Write-Host "[B-1] Applying tuned params (king-safety group)..."
& .\tools\apply-tuned-params.ps1 -Group king-safety -TunedParamsFile $TunedParamsFile
if ($LASTEXITCODE -ne 0) { Write-Error "apply-tuned-params failed"; exit 1 }

# ─── Step 4: Build tuned engine JAR ──────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host ""
    Write-Host "[B-1] Building tuned engine JAR..."
    & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Tuned build failed — restoring source files..."
        git checkout -- $KingSafetyFile $EvalParamsFile
        exit 1
    }
    if (-not (Test-Path $BuiltEngineJar)) { Write-Error "Tuned engine JAR not found: $BuiltEngineJar"; exit 1 }
    Copy-Item $BuiltEngineJar $TunedJar -Force
    Write-Host "[B-1] Tuned JAR saved: $TunedJar"
} else {
    Write-Host "[B-1] SkipBuild — assuming $TunedJar already exists."
    if (-not (Test-Path $TunedJar)) { Write-Error "Tuned JAR not found (SkipBuild): $TunedJar"; exit 1 }
}

# ─── Step 5: Run SPRT ────────────────────────────────────────────────────────
Write-Host ""
Write-Host "[B-1] Running SPRT: tuned king-safety vs baseline..."
try {
    & .\tools\sprt.ps1 `
        -New $TunedJar `
        -Old $BaselineJar `
        -Tag "phase13-b1-kingsafety-tuned" `
        -BonferroniM 1 `
        -Elo0 0 -Elo1 15 `
        -TC '60+0.6' `
        -Concurrency 6 `
        -EngineThreads 2 `
        -MinGames 800

    Write-Host ""
    Write-Host "[B-1] SPRT complete."
    Write-Host "[B-1] Review the result above."
    Write-Host "[B-1] If H1 accepted  → commit changes: git add $KingSafetyFile $EvalParamsFile && git commit"
    Write-Host "[B-1] If H0 accepted  → restore:        git checkout -- $KingSafetyFile $EvalParamsFile"
} catch {
    Write-Warning "B-1 SPRT failed: $_"
    Write-Host "[B-1] Restoring source files due to error..."
    git checkout -- $KingSafetyFile $EvalParamsFile
    exit 1
}
