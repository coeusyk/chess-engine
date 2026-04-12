<#
.SYNOPSIS
    Build and run SPRT tests for C-2: LMR Formula Constant Tuning.

.DESCRIPTION
    Tests LMR divisor values 1.386, 1.7, and 2.0 against the current baseline
    (divisor = 2*(ln2)^2 ≈ 0.961). Uses Bonferroni correction for 3 simultaneous
    hypotheses.

    After finding the best divisor, optionally tests the LMR threshold change
    (moveIndex >= 3 vs current >= 4).

    Run from the chess-engine/ directory:
        .\tools\run_c2_sprt.ps1

.PARAMETER Divisors
    One or more divisor values to test. Default: 1.386, 1.7, 2.0.

.PARAMETER SkipBuild
    If set, skips the Maven build and reuses JAR files already present in tools/.
#>
param(
    [double[]]$Divisors = @(1.386, 1.7, 2.0),
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SearcherFile  = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$OriginalLine  = '    private static final double LMR_LOG_DIVISOR = 2.0 * Math.log(2) * Math.log(2);'
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
if ($currentContent -notmatch [regex]::Escape($OriginalLine)) {
    Write-Error "LMR_LOG_DIVISOR line not found at expected value. Restore Searcher.java before running."
    exit 1
}

Write-Host "C-2 SPRT Experiment — LMR Formula Constant Tuning"
Write-Host "----------------------------------------------------"
Write-Host "Divisors to test : $($Divisors -join ', ')"
Write-Host "Current divisor  : 2*(ln2)^2 ≈ 0.961"
Write-Host "Baseline JAR     : $BaselineJar"
Write-Host "Bonferroni m     : $($Divisors.Count)"
Write-Host ""

$results = @{}

foreach ($div in $Divisors) {
    Write-Host "=== Divisor = $div ==="
    $patchedLine = "    private static final double LMR_LOG_DIVISOR = $div;"
    $jar = "tools\engine-uci-c2-div$($div -replace '\.','_').jar"

    try {
        if (-not $SkipBuild) {
            $patched = $currentContent -replace [regex]::Escape($OriginalLine), $patchedLine
            Set-Content $SearcherFile $patched -Encoding UTF8 -NoNewline
            Write-Host "[C-2] Patched: LMR_LOG_DIVISOR = $div"

            Write-Host "[C-2] Building..."
            & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
            if ($LASTEXITCODE -ne 0) { throw "Maven build failed for divisor=$div (exit $LASTEXITCODE)" }

            $builtJar = "engine-uci\target\engine-uci-${VersionSuffix}.jar"
            if (-not (Test-Path $builtJar)) { throw "Built JAR not found at: $builtJar" }
            Copy-Item $builtJar $jar -Force
            Write-Host "[C-2] JAR saved: $jar"
        } else {
            if (-not (Test-Path $jar)) { throw "SkipBuild set but JAR not found: $jar" }
            Write-Host "[C-2] Reusing existing JAR: $jar (SkipBuild)"
        }

        Write-Host "[C-2] Running SPRT (BonferroniM=$($Divisors.Count))..."
        & .\tools\sprt.ps1 `
            -New $jar `
            -Old $BaselineJar `
            -BonferroniM $Divisors.Count `
            -Tag "phase13-c2-div$($div -replace '\.','_')"

        $results[$div] = "COMPLETED"
    } catch {
        $results[$div] = "FAILED: $_"
        Write-Warning "C-2 divisor=$div failed: $_"
    } finally {
        $restored = Get-Content $SearcherFile -Raw
        if ($restored -match [regex]::Escape($patchedLine)) {
            $restored = $restored -replace [regex]::Escape($patchedLine), $OriginalLine
            Set-Content $SearcherFile $restored -Encoding UTF8 -NoNewline
            Write-Host "[C-2] Restored LMR_LOG_DIVISOR to original"
        }
        $currentContent = Get-Content $SearcherFile -Raw
    }
    Write-Host ""
}

Write-Host "=== C-2 SPRT Summary ==="
foreach ($div in $Divisors) {
    Write-Host "  divisor=$div : $($results[$div])"
}
Write-Host ""
Write-Host "If a divisor wins, run threshold test next:"
Write-Host "  Modify LMR entry condition (moveIndex >= 3 vs >= 4) and re-SPRT."
