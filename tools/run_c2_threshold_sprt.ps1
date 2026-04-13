<#
.SYNOPSIS
    Build and run SPRT test for C-2 combined: LMR divisor=1.7 + threshold 3 vs 4.

.DESCRIPTION
    Tests the combined change (LMR_LOG_DIVISOR=1.7 AND moveIndex >= 3) against
    divisor=1.7 only (moveIndex >= 4). Uses single SPRT (BonferroniM=1, bounds ±2.94).

    The "old" engine is tools\engine-uci-c2-div1_7.jar (already built by run_c2_sprt.ps1).
    Run from the chess-engine/ directory:
        .\tools\run_c2_threshold_sprt.ps1

.PARAMETER SkipBuild
    If set, skips the Maven build and reuses the JAR already present in tools/.
#>
param(
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SearcherFile    = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$OrigDivisorLine = '    private static final double LMR_LOG_DIVISOR = 2.0 * Math.log(2) * Math.log(2);'
$OrigThreshLine  = '                && moveIndex >= 4'
$WinDiv          = 1.7
$PatchedDivisor  = "    private static final double LMR_LOG_DIVISOR = ${WinDiv};"
$PatchedThresh   = '                && moveIndex >= 3'
$Div17Jar        = "tools\engine-uci-c2-div1_7.jar"   # existing divisor-only JAR (the "old")
$CombinedJar     = "tools\engine-uci-c2-div1_7-thresh3.jar"
$VersionSuffix   = "0.5.6-SNAPSHOT"

if (-not (Test-Path $SearcherFile)) {
    Write-Error "Searcher.java not found. Run from chess-engine/ directory."
    exit 1
}
if (-not (Test-Path $Div17Jar)) {
    Write-Error "Divisor-1.7 JAR not found at '$Div17Jar'. Run run_c2_sprt.ps1 first."
    exit 1
}

$currentContent = Get-Content $SearcherFile -Raw
if ($currentContent -notmatch [regex]::Escape($OrigDivisorLine)) {
    Write-Error "LMR_LOG_DIVISOR not at expected baseline value. Restore Searcher.java before running."
    exit 1
}
if ($currentContent -notmatch [regex]::Escape($OrigThreshLine)) {
    Write-Error "moveIndex >= 4 line not found. Restore Searcher.java before running."
    exit 1
}

Write-Host "C-2 Threshold Test — Combined: div=1.7 + moveIndex >= 3 vs div=1.7 alone"
Write-Host "--------------------------------------------------------------------------"
Write-Host "New engine : LMR_LOG_DIVISOR=$WinDiv AND moveIndex >= 3"
Write-Host "Old engine : $Div17Jar (divisor=1.7, threshold >= 4)"
Write-Host "SPRT bounds: single test, BonferroniM=1 => ±2.94"
Write-Host ""

try {
    if (-not $SkipBuild) {
        # Patch both the divisor and the threshold
        $patched = $currentContent `
            -replace [regex]::Escape($OrigDivisorLine), $PatchedDivisor `
            -replace [regex]::Escape($OrigThreshLine),  $PatchedThresh
        Set-Content $SearcherFile $patched -Encoding UTF8 -NoNewline
        Write-Host "[C-2-thresh] Patched: LMR_LOG_DIVISOR=$WinDiv AND moveIndex >= 3"

        Write-Host "[C-2-thresh] Building..."
        & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)" }

        $builtJar = "engine-uci\target\engine-uci-${VersionSuffix}.jar"
        if (-not (Test-Path $builtJar)) { throw "Built JAR not found at: $builtJar" }
        Copy-Item $builtJar $CombinedJar -Force
        Write-Host "[C-2-thresh] JAR saved: $CombinedJar"
    } else {
        if (-not (Test-Path $CombinedJar)) { throw "SkipBuild set but JAR not found: $CombinedJar" }
        Write-Host "[C-2-thresh] Reusing existing JAR: $CombinedJar (SkipBuild)"
    }

    Write-Host "[C-2-thresh] Running SPRT (BonferroniM=1, single hypothesis)..."
    & .\tools\sprt.ps1 `
        -New $CombinedJar `
        -Old $Div17Jar `
        -BonferroniM 1 `
        -Tag "phase13-c2-thresh3"

} catch {
    Write-Warning "C-2 threshold test failed: $_"
    throw
} finally {
    # Restore both patched lines
    $restored = Get-Content $SearcherFile -Raw
    $needsRestore = $false
    if ($restored -match [regex]::Escape($PatchedDivisor)) {
        $restored = $restored -replace [regex]::Escape($PatchedDivisor), $OrigDivisorLine
        $needsRestore = $true
    }
    if ($restored -match [regex]::Escape($PatchedThresh)) {
        $restored = $restored -replace [regex]::Escape($PatchedThresh), $OrigThreshLine
        $needsRestore = $true
    }
    if ($needsRestore) {
        Set-Content $SearcherFile $restored -Encoding UTF8 -NoNewline
        Write-Host "[C-2-thresh] Restored Searcher.java to original"
    }
}

Write-Host ""
Write-Host "=== C-2 Threshold Test Complete ==="
Write-Host "  If H1: adopt divisor=1.7 + moveIndex>=3 as the final C-2 change."
Write-Host "  If H0/inconclusive: adopt divisor=1.7 + moveIndex>=4 only."
