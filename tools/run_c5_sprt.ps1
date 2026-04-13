<#
.SYNOPSIS
    Build and run SPRT tests for C-5: Null Move Pruning Depth Boundary.

.DESCRIPTION
    Tests NULL_MOVE_DEPTH_THRESHOLD values 2 and 4 against the current 3.

    Candidate A: threshold = 2  (NMP fires one ply earlier)
    Candidate B: threshold = 4  (NMP fires one ply later)

    SPRT settings per the Phase 13 spec:
      H0=0 Elo, H1=50 Elo, α=β=0.025 (Bonferroni M=2, ±3.66 bounds)
      TC: 60+0.6 — Concurrency: 4 games, 2 threads/engine — Min 600 games

    Run from the chess-engine/ directory:
        .\tools\run_c5_sprt.ps1

.PARAMETER Thresholds
    One or more NULL_MOVE_DEPTH_THRESHOLD values to test. Default: 2, 4.

.PARAMETER SkipBuild
    If set, skips the Maven build and reuses JAR files already present in tools/.
#>
param(
    [int[]]$Thresholds = @(2, 4),
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SearcherFile       = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$OrigThreshValue    = 4
$ThreshConstant     = '    private static final int NULL_MOVE_DEPTH_THRESHOLD = '
$ExpectedThresh     = "${ThreshConstant}${OrigThreshValue};"
$BaselineJar        = "tools\baseline-v0.5.6-pretune.jar"
$VersionSuffix      = "0.5.6-SNAPSHOT"

if (-not (Test-Path $SearcherFile)) {
    Write-Error "Searcher.java not found at '$SearcherFile'. Run from chess-engine/ directory."
    exit 1
}
if (-not (Test-Path $BaselineJar)) {
    Write-Error "Baseline JAR not found: $BaselineJar"
    exit 1
}

$currentContent = Get-Content $SearcherFile -Raw
if ($currentContent -notmatch [regex]::Escape($ExpectedThresh)) {
    Write-Error "NULL_MOVE_DEPTH_THRESHOLD not at expected value ($OrigThreshValue). Restore Searcher.java before running."
    exit 1
}

Write-Host "C-5 SPRT Experiment — Null Move Pruning Depth Boundary"
Write-Host "-------------------------------------------------------"
Write-Host "Parameter        : NULL_MOVE_DEPTH_THRESHOLD"
Write-Host "Current value    : $OrigThreshValue"
Write-Host "Values to test   : $($Thresholds -join ', ')"
Write-Host "Baseline JAR     : $BaselineJar"
Write-Host "SPRT settings    : H0=0/H1=50 Elo, BonferroniM=2, TC=60+0.6, conc=4, threads=2, minGames=600"
Write-Host ""

$results = @{}

foreach ($thresh in $Thresholds) {
    $Jar = "tools\engine-uci-c5-nmpthresh${thresh}.jar"

    Write-Host ""
    Write-Host "=== NULL_MOVE_DEPTH_THRESHOLD = $thresh ==="
    Write-Host ""

    try {
        if (-not $SkipBuild) {
            $patchedContent = $currentContent -replace [regex]::Escape($ExpectedThresh), "${ThreshConstant}${thresh};"
            Set-Content $SearcherFile $patchedContent -Encoding UTF8 -NoNewline
            Write-Host "[C-5] Patched NULL_MOVE_DEPTH_THRESHOLD to $thresh"

            Write-Host "[C-5] Building..."
            & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
            if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)" }

            $builtJar = "engine-uci\target\engine-uci-${VersionSuffix}.jar"
            if (-not (Test-Path $builtJar)) { throw "Built JAR not found at: $builtJar" }
            Copy-Item $builtJar $Jar -Force
            Write-Host "[C-5] JAR saved: $Jar"
        } else {
            if (-not (Test-Path $Jar)) { throw "SkipBuild set but JAR not found: $Jar" }
            Write-Host "[C-5] Reusing existing JAR: $Jar (SkipBuild)"
        }

        Write-Host "[C-5] Running SPRT for threshold=$thresh..."
        & .\tools\sprt.ps1 `
            -New $Jar `
            -Old $BaselineJar `
            -Tag "phase13-c5-nmpthresh${thresh}" `
            -BonferroniM 2 `
            -Elo0 0 -Elo1 50 `
            -TC '60+0.6' `
            -Concurrency 4 `
            -EngineThreads 2 `
            -MinGames 600

        $results[$thresh] = "COMPLETED"
    } catch {
        Write-Warning "C-5 threshold=$thresh failed: $_"
        $results[$thresh] = "FAILED: $_"
    } finally {
        # Always restore Searcher.java
        $restored = Get-Content $SearcherFile -Raw
        $patchLine = "${ThreshConstant}${thresh};"
        if ($restored -match [regex]::Escape($patchLine)) {
            $restored = $restored -replace [regex]::Escape($patchLine), $ExpectedThresh
            Set-Content $SearcherFile $restored -Encoding UTF8 -NoNewline
            Write-Host "[C-5] Restored NULL_MOVE_DEPTH_THRESHOLD to $OrigThreshValue"
        }
        $currentContent = Get-Content $SearcherFile -Raw
    }
}

Write-Host ""
Write-Host "=== C-5 SPRT Summary ==="
foreach ($thresh in $Thresholds) {
    Write-Host "  threshold=$thresh : $($results[$thresh])"
}
Write-Host "========================="
