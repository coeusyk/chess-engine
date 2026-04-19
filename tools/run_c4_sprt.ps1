<#
.SYNOPSIS
    Build and run SPRT tests for C-4: Singular Extension Margin Tuning.

.DESCRIPTION
    Tests SINGULAR_EXTENSION_MARGIN base offsets -10 and +10 against the
    current baseline value of 0 cp (effective margin = depth*8 + offset).

    Candidate A: offset = -10  (tighter margin → more singular extensions)
    Candidate B: offset = +10  (looser  margin → fewer singular extensions)

    SPRT settings per the Phase 13 spec:
      H0=0 Elo, H1=50 Elo, α=β=0.025 (Bonferroni M=2, ±3.66 bounds)
      TC: 60+0.6 — Concurrency: 4 games, 2 threads/engine — Min 600 games

    Run from the chess-engine/ directory:
        .\tools\run_c4_sprt.ps1

.PARAMETER Offsets
    One or more SINGULAR_EXTENSION_MARGIN offsets to test. Default: -10, 10.

.PARAMETER SkipBuild
    If set, skips the Maven build and reuses JAR files already present in tools/.
#>
param(
    [int[]]$Offsets = @(-10, 10),
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SearcherFile       = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$OrigOffsetValue    = -10
$OffsetConstant     = '    private static final int SINGULAR_EXTENSION_MARGIN = '
$ExpectedOffset     = "${OffsetConstant}${OrigOffsetValue};"
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
if ($currentContent -notmatch [regex]::Escape($ExpectedOffset)) {
    Write-Error "SINGULAR_EXTENSION_MARGIN not at expected value ($OrigOffsetValue). Restore Searcher.java before running."
    exit 1
}

Write-Host "C-4 SPRT Experiment — Singular Extension Margin Tuning"
Write-Host "-------------------------------------------------------"
Write-Host "Parameter        : SINGULAR_EXTENSION_MARGIN (base offset)"
Write-Host "Current value    : $OrigOffsetValue cp"
Write-Host "Offsets to test  : $($Offsets -join ', ')"
Write-Host "Formula          : getSingularMargin(depth) = depth*SINGULAR_MARGIN_PER_PLY + offset"
Write-Host "Baseline JAR     : $BaselineJar"
Write-Host "SPRT settings    : H0=0/H1=50 Elo, BonferroniM=2, TC=60+0.6, conc=4, threads=2, minGames=600"
Write-Host ""

$results = @{}

foreach ($offset in $Offsets) {
    $safeName = if ($offset -lt 0) { "neg$([Math]::Abs($offset))" } else { "pos$offset" }
    $Jar = "tools\engine-uci-c4-singext${safeName}.jar"

    Write-Host ""
    Write-Host "=== SINGULAR_EXTENSION_MARGIN = $offset ==="
    Write-Host ""

    try {
        if (-not $SkipBuild) {
            $patchedContent = $currentContent -replace [regex]::Escape($ExpectedOffset), "${OffsetConstant}${offset};"
            Set-Content $SearcherFile $patchedContent -Encoding UTF8 -NoNewline
            Write-Host "[C-4] Patched SINGULAR_EXTENSION_MARGIN to $offset"

            Write-Host "[C-4] Building..."
            & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
            if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)" }

            $builtJar = "engine-uci\target\engine-uci-${VersionSuffix}.jar"
            if (-not (Test-Path $builtJar)) { throw "Built JAR not found at: $builtJar" }
            Copy-Item $builtJar $Jar -Force
            Write-Host "[C-4] JAR saved: $Jar"
        } else {
            if (-not (Test-Path $Jar)) { throw "SkipBuild set but JAR not found: $Jar" }
            Write-Host "[C-4] Reusing existing JAR: $Jar (SkipBuild)"
        }

        Write-Host "[C-4] Running SPRT for offset=$offset..."
        & .\tools\sprt.ps1 `
            -New $Jar `
            -Old $BaselineJar `
            -Tag "phase13-c4-singext${safeName}" `
            -BonferroniM 2 `
            -Elo0 0 -Elo1 50 `
            -TC '60+0.6' `
            -Concurrency 4 `
            -EngineThreads 2 `
            -MinGames 600

        $results[$offset] = "COMPLETED"
    } catch {
        Write-Warning "C-4 offset=$offset failed: $_"
        $results[$offset] = "FAILED: $_"
    } finally {
        # Always restore Searcher.java
        $restored = Get-Content $SearcherFile -Raw
        $patchLine = "${OffsetConstant}${offset};"
        if ($restored -match [regex]::Escape($patchLine)) {
            $restored = $restored -replace [regex]::Escape($patchLine), $ExpectedOffset
            Set-Content $SearcherFile $restored -Encoding UTF8 -NoNewline
            Write-Host "[C-4] Restored SINGULAR_EXTENSION_MARGIN to $OrigOffsetValue"
        }
        # Reload currentContent from restored file for next iteration
        $currentContent = Get-Content $SearcherFile -Raw
    }
}

Write-Host ""
Write-Host "=== C-4 SPRT Summary ==="
foreach ($offset in $Offsets) {
    $safeName = if ($offset -lt 0) { "neg$([Math]::Abs($offset))" } else { "pos$offset" }
    Write-Host "  offset=$offset : $($results[$offset])"
}
Write-Host "========================="
