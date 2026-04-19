<#
.SYNOPSIS
    B-2: Pawn-Structure Texel tuning + SPRT.

.DESCRIPTION
    Step 1 — Merge passed_pawn_seeds, connected_pawn_seeds, backward_pawn_seeds
             (FEN-dedup) → tools/seeds/pawn_structure_combined.epd.
    Step 2 — Rebuild engine-tuner with current eval constants.
    Step 3 — Run Texel/Adam tuning (--param-group pawn-structure, 200 iterations).
    Step 4 — Apply tuned_params.txt to PawnStructure.java and EvalParams.java.
    Step 5 — Build tuned engine-uci JAR.
    Step 6 — SPRT vs. a pre-tuning baseline JAR:
                H0=0 Elo, H1=15 Elo, α=β=0.05
                TC: 60+0.6 — 6 concurrent games, 2 threads/engine, min 800 games.

    If SPRT accepts H0, source files are restored via git checkout.

    Run from the chess-engine/ directory:
        .\tools\run_b2_sprt.ps1

.PARAMETER SkipMerge
    Skip seed-file merging; assume pawn_structure_combined.epd already exists.

.PARAMETER SkipTune
    Skip tuner run; reuse existing tuned_params.txt and tuned JAR.

.PARAMETER SkipBuild
    Skip all Maven builds; assume all JARs already present in tools/.
#>
param(
    [switch]$SkipMerge,
    [switch]$SkipTune,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ─── Paths ───────────────────────────────────────────────────────────────────
$VersionSuffix   = "0.5.6-SNAPSHOT"
$TunerJar        = "engine-tuner\target\engine-tuner-${VersionSuffix}-shaded.jar"
$SeedDir         = "tools\seeds"
$SeedFiles       = @(
    "$SeedDir\passed_pawn_seeds.epd",
    "$SeedDir\connected_pawn_seeds.epd",
    "$SeedDir\backward_pawn_seeds.epd"
)
$CombinedSeed    = "$SeedDir\pawn_structure_combined.epd"
$TunedParamsFile = "tuned_params.txt"
$BuiltEngineJar  = "engine-uci\target\engine-uci-${VersionSuffix}.jar"
$BaselineJar     = "tools\engine-uci-b2-baseline.jar"
$TunedJar        = "tools\engine-uci-b2-pawnstruct-tuned.jar"

# Source files modified by group=pawn-structure
$PawnStructFile  = "engine-core\src\main\java\coeusyk\game\chess\core\eval\PawnStructure.java"
$EvalParamsFile  = "engine-tuner\src\main\java\coeusyk\game\chess\tuner\EvalParams.java"

Write-Host "B-2 Experiment — Pawn-Structure Texel Tuning + SPRT"
Write-Host "------------------------------------------------------"
Write-Host "Seed inputs  : $($SeedFiles -join ', ')"
Write-Host "Combined     : $CombinedSeed"
Write-Host "Iterations   : 200 (Adam optimizer, freeze-k)"
Write-Host "SPRT         : H0=0 / H1=15 Elo, alpha=beta=0.05"
Write-Host "TC           : 60+0.6  |  6 concurrent  |  2 threads/engine  |  min 800 games"
Write-Host ""

# ─── Step 1: Merge and dedup seed files ──────────────────────────────────────
if (-not $SkipMerge) {
    foreach ($f in $SeedFiles) {
        if (-not (Test-Path $f)) { Write-Error "Seed file not found: $f"; exit 1 }
    }
    Write-Host "[B-2] Merging and deduplicating pawn seed files..."
    $lines = $SeedFiles | ForEach-Object { Get-Content $_ }
    $deduped = $lines | Sort-Object -Unique
    Set-Content $CombinedSeed $deduped -Encoding UTF8
    Write-Host "[B-2] Combined seed: $($deduped.Count) unique positions → $CombinedSeed"
} else {
    if (-not (Test-Path $CombinedSeed)) { Write-Error "Combined seed not found (SkipMerge): $CombinedSeed"; exit 1 }
    Write-Host "[B-2] SkipMerge — reusing $CombinedSeed"
}

# ─── Step 2: Build baseline JAR (current source, before tuning) ──────────────
if (-not $SkipBuild) {
    Write-Host ""
    Write-Host "[B-2] Building baseline JAR from current HEAD..."
    & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Error "Maven build (baseline) failed"; exit 1 }
    if (-not (Test-Path $BuiltEngineJar)) { Write-Error "Baseline engine JAR not found: $BuiltEngineJar"; exit 1 }
    Copy-Item $BuiltEngineJar $BaselineJar -Force
    Write-Host "[B-2] Baseline JAR saved: $BaselineJar"

    Write-Host "[B-2] Building engine-tuner..."
    & .\mvnw.cmd package -pl engine-tuner -am -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Error "Maven build (tuner) failed"; exit 1 }
    Write-Host "[B-2] Tuner JAR: $TunerJar"
} else {
    Write-Host "[B-2] SkipBuild — assuming $BaselineJar and $TunerJar already exist."
    if (-not (Test-Path $BaselineJar)) { Write-Error "Baseline JAR not found (SkipBuild): $BaselineJar"; exit 1 }
    if (-not (Test-Path $TunerJar))    { Write-Error "Tuner JAR not found (SkipBuild): $TunerJar"; exit 1 }
}

# ─── Step 3: Run Texel tuning ────────────────────────────────────────────────
if (-not $SkipTune) {
    Write-Host ""
    Write-Host "[B-2] Running Texel tuning (pawn-structure, 200 iterations)..."
    Write-Host ""
    & java -jar $TunerJar $CombinedSeed 5000000 200 --optimizer adam --param-group pawn-structure --freeze-k
    if ($LASTEXITCODE -ne 0) { Write-Error "Tuner exited with error $LASTEXITCODE"; exit 1 }
    Write-Host ""
    Write-Host "[B-2] Tuning complete. Reading $TunedParamsFile..."
    Get-Content $TunedParamsFile | Select-String -Pattern 'PASSED|ISOLATED|DOUBLED|BACKWARD|CONNECTED'
} else {
    Write-Host "[B-2] SkipTune — reusing existing $TunedParamsFile"
    if (-not (Test-Path $TunedParamsFile)) { Write-Error "tuned_params.txt not found (SkipTune)"; exit 1 }
}

# ─── Step 4: Apply tuned params ──────────────────────────────────────────────
Write-Host ""
Write-Host "[B-2] Applying tuned params (pawn-structure group)..."
& .\tools\apply-tuned-params.ps1 -Group pawn-structure -TunedParamsFile $TunedParamsFile
if ($LASTEXITCODE -ne 0) { Write-Error "apply-tuned-params failed"; exit 1 }

# ─── Step 5: Build tuned engine JAR ──────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host ""
    Write-Host "[B-2] Building tuned engine JAR..."
    & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Tuned build failed — restoring source files..."
        git checkout -- $PawnStructFile $EvalParamsFile
        exit 1
    }
    if (-not (Test-Path $BuiltEngineJar)) { Write-Error "Tuned engine JAR not found: $BuiltEngineJar"; exit 1 }
    Copy-Item $BuiltEngineJar $TunedJar -Force
    Write-Host "[B-2] Tuned JAR saved: $TunedJar"
} else {
    Write-Host "[B-2] SkipBuild — assuming $TunedJar already exists."
    if (-not (Test-Path $TunedJar)) { Write-Error "Tuned JAR not found (SkipBuild): $TunedJar"; exit 1 }
}

# ─── Step 6: Run SPRT ────────────────────────────────────────────────────────
Write-Host ""
Write-Host "[B-2] Running SPRT: tuned pawn-structure vs baseline..."
try {
    & .\tools\sprt.ps1 `
        -New $TunedJar `
        -Old $BaselineJar `
        -Tag "phase13-b2-pawnstruct-tuned" `
        -BonferroniM 1 `
        -Elo0 0 -Elo1 15 `
        -TC '60+0.6' `
        -Concurrency 6 `
        -EngineThreads 2 `
        -MinGames 800

    Write-Host ""
    Write-Host "[B-2] SPRT complete."
    Write-Host "[B-2] Review the result above."
    Write-Host "[B-2] If H1 accepted  → commit: git add $PawnStructFile $EvalParamsFile && git commit"
    Write-Host "[B-2] If H0 accepted  → restore: git checkout -- $PawnStructFile $EvalParamsFile"
} catch {
    Write-Warning "B-2 SPRT failed: $_"
    Write-Host "[B-2] Restoring source files due to error..."
    git checkout -- $PawnStructFile $EvalParamsFile
    exit 1
}
