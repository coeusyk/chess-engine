<#
.SYNOPSIS
    Build and run SPRT tests for C-4: Singular Extension Margin Tuning.

.DESCRIPTION
    Tests SINGULAR_MARGIN_PER_PLY values 4 and 2 against the current 8 cp.
    Then optionally tests SINGULAR_DEPTH_THRESHOLD = 6 vs current 8 at the
    best margin.

    Run from the chess-engine/ directory:
        .\tools\run_c4_sprt.ps1

.PARAMETER Margins
    One or more margin-per-ply values to test. Default: 4, 2.

.PARAMETER SkipBuild
    If set, skips the Maven build and reuses JAR files already present in tools/.
#>
param(
    [int[]]$Margins = @(4, 2),
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SearcherFile     = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$OrigMarginValue  = 8
$MarginBaseLine   = '    private static final int SINGULAR_MARGIN_PER_PLY = '
$ExpectedMargin   = "${MarginBaseLine}${OrigMarginValue};"
$OrigThreshValue  = 8
$ThreshBaseLine   = '    private static final int SINGULAR_DEPTH_THRESHOLD = '
$ExpectedThresh   = "${ThreshBaseLine}${OrigThreshValue};"
$BaselineJar      = "tools\baseline-v0.5.6-pretune.jar"
$VersionSuffix    = "0.5.6-SNAPSHOT"

if (-not (Test-Path $SearcherFile)) {
    Write-Error "Searcher.java not found at '$SearcherFile'. Run from chess-engine/ directory."
    exit 1
}
if (-not (Test-Path $BaselineJar)) {
    Write-Error "Baseline JAR not found: $BaselineJar"
    exit 1
}

$currentContent = Get-Content $SearcherFile -Raw
if ($currentContent -notmatch [regex]::Escape($ExpectedMargin)) {
    Write-Error "SINGULAR_MARGIN_PER_PLY not at expected value ($OrigMarginValue). Restore Searcher.java before running."
    exit 1
}

Write-Host "C-4 SPRT Experiment — Singular Extension Margin Tuning"
Write-Host "-------------------------------------------------------"
Write-Host "Margins to test  : $($Margins -join ', ')"
Write-Host "Current margin   : $OrigMarginValue cp/ply"
Write-Host "Baseline JAR     : $BaselineJar"
Write-Host "Bonferroni m     : $($Margins.Count)"
Write-Host ""

$results = @{}

# Phase 1: Test margin per ply values
foreach ($margin in $Margins) {
    Write-Host "=== SINGULAR_MARGIN_PER_PLY = $margin ==="
    $patchedLine = "${MarginBaseLine}${margin};"
    $jar = "tools\engine-uci-c4-singmar$margin.jar"

    try {
        if (-not $SkipBuild) {
            $patched = $currentContent -replace [regex]::Escape($ExpectedMargin), $patchedLine
            Set-Content $SearcherFile $patched -Encoding UTF8 -NoNewline
            Write-Host "[C-4] Patched: SINGULAR_MARGIN_PER_PLY = $margin"

            Write-Host "[C-4] Building..."
            & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
            if ($LASTEXITCODE -ne 0) { throw "Maven build failed for margin=$margin (exit $LASTEXITCODE)" }

            $builtJar = "engine-uci\target\engine-uci-${VersionSuffix}.jar"
            if (-not (Test-Path $builtJar)) { throw "Built JAR not found at: $builtJar" }
            Copy-Item $builtJar $jar -Force
            Write-Host "[C-4] JAR saved: $jar"
        } else {
            if (-not (Test-Path $jar)) { throw "SkipBuild set but JAR not found: $jar" }
            Write-Host "[C-4] Reusing existing JAR: $jar (SkipBuild)"
        }

        Write-Host "[C-4] Running SPRT (BonferroniM=$($Margins.Count))..."
        & .\tools\sprt.ps1 `
            -New $jar `
            -Old $BaselineJar `
            -BonferroniM $Margins.Count `
            -Tag "phase13-c4-singmar$margin"

        $results[$margin] = "COMPLETED"
    } catch {
        $results[$margin] = "FAILED: $_"
        Write-Warning "C-4 margin=$margin failed: $_"
    } finally {
        $restored = Get-Content $SearcherFile -Raw
        if ($restored -match [regex]::Escape($patchedLine)) {
            $restored = $restored -replace [regex]::Escape($patchedLine), $ExpectedMargin
            Set-Content $SearcherFile $restored -Encoding UTF8 -NoNewline
            Write-Host "[C-4] Restored SINGULAR_MARGIN_PER_PLY to $OrigMarginValue"
        }
        $currentContent = Get-Content $SearcherFile -Raw
    }
    Write-Host ""
}

Write-Host "=== C-4 Margin SPRT Summary ==="
foreach ($margin in $Margins) {
    Write-Host "  margin=$margin : $($results[$margin])"
}
Write-Host ""
Write-Host "After identifying the best margin, run the threshold test:"
Write-Host "  .\tools\run_c4_sprt.ps1 -Margins @() then manually test SINGULAR_DEPTH_THRESHOLD=6"
Write-Host ""

# Phase 2: Test threshold (manual step after phase 1 results are reviewed)
# The user should set the best margin from phase 1, then run:
#   Modify SINGULAR_DEPTH_THRESHOLD from 8 to 6 and SPRT manually.
Write-Host "NOTE: C-4c (SINGULAR_DEPTH_THRESHOLD=6) should be tested manually after"
Write-Host "choosing the best margin from C-4a/C-4b."
