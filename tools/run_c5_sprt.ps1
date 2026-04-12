<#
.SYNOPSIS
    Build and run SPRT test for C-5: Null Move Reduction Boundary.

.DESCRIPTION
    Tests changing the null move R=3 boundary from effectiveDepth > 6
    to effectiveDepth > 5 (R=3 kicks in one depth earlier).

    Run from the chess-engine/ directory:
        .\tools\run_c5_sprt.ps1

.PARAMETER SkipBuild
    If set, skips the Maven build and reuses JAR file already present in tools/.
#>
param(
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SearcherFile  = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$OriginalLine  = '            int nullReduction = effectiveDepth > 6 ? 3 : 2;'
$PatchedLine   = '            int nullReduction = effectiveDepth > 5 ? 3 : 2;'
$BaselineJar   = "tools\baseline-v0.5.6-pretune.jar"
$VersionSuffix = "0.5.6-SNAPSHOT"
$Jar           = "tools\engine-uci-c5-nullr3d5.jar"

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
    Write-Error "Null move boundary line not found at expected value. Restore Searcher.java before running."
    exit 1
}

Write-Host "C-5 SPRT Experiment — Null Move Reduction Boundary"
Write-Host "----------------------------------------------------"
Write-Host "Change           : effectiveDepth > 6 -> effectiveDepth > 5"
Write-Host "Effect           : R=3 kicks in one depth earlier"
Write-Host "Baseline JAR     : $BaselineJar"
Write-Host ""

try {
    if (-not $SkipBuild) {
        $patched = $currentContent -replace [regex]::Escape($OriginalLine), $PatchedLine
        Set-Content $SearcherFile $patched -Encoding UTF8 -NoNewline
        Write-Host "[C-5] Patched: effectiveDepth > 6 -> effectiveDepth > 5"

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

    Write-Host "[C-5] Running SPRT..."
    & .\tools\sprt.ps1 `
        -New $Jar `
        -Old $BaselineJar `
        -Tag "phase13-c5-nullr3d5"

    Write-Host "[C-5] COMPLETED"
} catch {
    Write-Warning "C-5 failed: $_"
} finally {
    $restored = Get-Content $SearcherFile -Raw
    if ($restored -match [regex]::Escape($PatchedLine)) {
        $restored = $restored -replace [regex]::Escape($PatchedLine), $OriginalLine
        Set-Content $SearcherFile $restored -Encoding UTF8 -NoNewline
        Write-Host "[C-5] Restored null move boundary to effectiveDepth > 6"
    }
}
