<#
.SYNOPSIS
    Build and run SPRT tests for C-3: Futility Margin Depth-1 Tuning.

.DESCRIPTION
    Tests FUTILITY_MARGIN_DEPTH_1 values 125 and 175 against the current 150 cp.
    Uses Bonferroni correction for 2 simultaneous hypotheses.

    Run from the chess-engine/ directory:
        .\tools\run_c3_sprt.ps1

.PARAMETER Margins
    One or more margin values to test. Default: 125, 175.

.PARAMETER SkipBuild
    If set, skips the Maven build and reuses JAR files already present in tools/.
#>
param(
    [int[]]$Margins = @(125, 175),
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SearcherFile  = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$OriginalValue = 150
$BaseLine      = '    private static final int FUTILITY_MARGIN_DEPTH_1 = '
$ExpectedOrig  = "${BaseLine}${OriginalValue};"
$BaselineJar   = "tools\baseline-v0.5.6-pretune.jar"
$VersionSuffix = "0.5.6-SNAPSHOT"

if (-not (Test-Path $SearcherFile)) {
    Write-Error "Searcher.java not found at '$SearcherFile'. Run from chess-engine/ directory."
    exit 1
}
if (-not (Test-Path $BaselineJar)) {
    Write-Error "Baseline JAR not found: $BaselineJar"
    exit 1
}

$currentContent = Get-Content $SearcherFile -Raw
if ($currentContent -notmatch [regex]::Escape($ExpectedOrig)) {
    Write-Error "FUTILITY_MARGIN_DEPTH_1 not at expected value ($OriginalValue). Restore Searcher.java before running."
    exit 1
}

Write-Host "C-3 SPRT Experiment — Futility Margin Depth-1 Tuning"
Write-Host "------------------------------------------------------"
Write-Host "Margins to test  : $($Margins -join ', ')"
Write-Host "Current margin   : $OriginalValue cp"
Write-Host "Baseline JAR     : $BaselineJar"
Write-Host "Bonferroni m     : $($Margins.Count)"
Write-Host ""

$results = @{}

foreach ($margin in $Margins) {
    Write-Host "=== FUTILITY_MARGIN_DEPTH_1 = $margin ==="
    $patchedLine = "${BaseLine}${margin};"
    $jar = "tools\engine-uci-c3-fut$margin.jar"

    try {
        if (-not $SkipBuild) {
            $patched = $currentContent -replace [regex]::Escape($ExpectedOrig), $patchedLine
            Set-Content $SearcherFile $patched -Encoding UTF8 -NoNewline
            Write-Host "[C-3] Patched: FUTILITY_MARGIN_DEPTH_1 = $margin"

            Write-Host "[C-3] Building..."
            & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
            if ($LASTEXITCODE -ne 0) { throw "Maven build failed for margin=$margin (exit $LASTEXITCODE)" }

            $builtJar = "engine-uci\target\engine-uci-${VersionSuffix}.jar"
            if (-not (Test-Path $builtJar)) { throw "Built JAR not found at: $builtJar" }
            Copy-Item $builtJar $jar -Force
            Write-Host "[C-3] JAR saved: $jar"
        } else {
            if (-not (Test-Path $jar)) { throw "SkipBuild set but JAR not found: $jar" }
            Write-Host "[C-3] Reusing existing JAR: $jar (SkipBuild)"
        }

        Write-Host "[C-3] Running SPRT (BonferroniM=$($Margins.Count))..."
        & .\tools\sprt.ps1 `
            -New $jar `
            -Old $BaselineJar `
            -BonferroniM $Margins.Count `
            -Tag "phase13-c3-fut$margin"

        $results[$margin] = "COMPLETED"
    } catch {
        $results[$margin] = "FAILED: $_"
        Write-Warning "C-3 margin=$margin failed: $_"
    } finally {
        $restored = Get-Content $SearcherFile -Raw
        if ($restored -match [regex]::Escape($patchedLine)) {
            $restored = $restored -replace [regex]::Escape($patchedLine), $ExpectedOrig
            Set-Content $SearcherFile $restored -Encoding UTF8 -NoNewline
            Write-Host "[C-3] Restored FUTILITY_MARGIN_DEPTH_1 to $OriginalValue"
        }
        $currentContent = Get-Content $SearcherFile -Raw
    }
    Write-Host ""
}

Write-Host "=== C-3 SPRT Summary ==="
foreach ($margin in $Margins) {
    Write-Host "  margin=$margin : $($results[$margin])"
}
Write-Host ""
Write-Host "Best margin from above can be used as the new default."
Write-Host "Next: consider C-3c (re-enable razoring) if futility results are positive."
