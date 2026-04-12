<#
.SYNOPSIS
    Build and run SPRT tests for C-1: Aspiration Window Initial Delta experiment.

.DESCRIPTION
    Tests aspiration window initial delta values 25, 40, and 75 cp against the
    baseline JAR (0.4.9). Uses Bonferroni correction for 3 simultaneous hypotheses.
    ASPIRATION_INITIAL_DELTA_CP is restored to 50 at the end regardless of outcome.

    Run from the chess-engine/ directory:
        .\tools\run_c1_sprt.ps1

    To test a single delta only:
        .\tools\run_c1_sprt.ps1 -Deltas 40

.PARAMETER Deltas
    One or more delta values to test. Default: 25, 40, 75.

.PARAMETER SkipBuild
    If set, skips the Maven build and reuses JAR files already present in tools/.
    Useful for re-running SPRTs after a partial failure.
#>
param(
    [int[]]$Deltas = @(25, 40, 75),
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SearcherFile  = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$OriginalValue = 50
$BaseLine      = '    private static final int ASPIRATION_INITIAL_DELTA_CP = '
$ExpectedOrig  = "${BaseLine}${OriginalValue};"
$BaselineJar   = "tools\engine-uci-0.4.9.jar"
$VersionSuffix = "0.5.6-SNAPSHOT-shaded"

if (-not (Test-Path $SearcherFile)) {
    Write-Error "Searcher.java not found at '$SearcherFile'. Run from chess-engine/ directory."
    exit 1
}
if (-not (Test-Path $BaselineJar)) {
    Write-Error "Baseline JAR not found: $BaselineJar"
    exit 1
}

# Verify original constant is at expected value before touching anything
$currentContent = Get-Content $SearcherFile -Raw
if ($currentContent -notmatch [regex]::Escape($ExpectedOrig)) {
    Write-Error "ASPIRATION_INITIAL_DELTA_CP is not currently $OriginalValue. Restore it to $OriginalValue before running this script."
    exit 1
}

Write-Host "C-1 SPRT Experiment — Aspiration Window Initial Delta"
Write-Host "-------------------------------------------------------"
Write-Host "Deltas to test : $($Deltas -join ', ') cp"
Write-Host "Baseline JAR   : $BaselineJar"
Write-Host "Bonferroni m   : $($Deltas.Count)"
Write-Host ""

$results = @{}

foreach ($delta in $Deltas) {
    Write-Host "=== Delta = $delta cp ==="
    $patchedLine = "${BaseLine}${delta};"
    $jar = "tools\engine-uci-c1-delta${delta}.jar"

    try {
        if (-not $SkipBuild) {
            # Patch source
            $patched = $currentContent -replace [regex]::Escape($ExpectedOrig), $patchedLine
            Set-Content $SearcherFile $patched -Encoding UTF8 -NoNewline
            Write-Host "[C-1] Patched: ASPIRATION_INITIAL_DELTA_CP = $delta"

            # Build engine-uci (includes engine-core via -am)
            Write-Host "[C-1] Building..."
            & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
            if ($LASTEXITCODE -ne 0) { throw "Maven build failed for delta=$delta (exit $LASTEXITCODE)" }

            # Copy JAR
            $builtJar = "engine-uci\target\engine-uci-${VersionSuffix}.jar"
            if (-not (Test-Path $builtJar)) {
                throw "Built JAR not found at: $builtJar"
            }
            Copy-Item $builtJar $jar -Force
            Write-Host "[C-1] JAR saved: $jar"
        } else {
            if (-not (Test-Path $jar)) { throw "SkipBuild set but JAR not found: $jar" }
            Write-Host "[C-1] Reusing existing JAR: $jar (SkipBuild)"
        }

        # Run SPRT
        Write-Host "[C-1] Running SPRT (BonferroniM=$($Deltas.Count))..."
        & .\tools\sprt.ps1 `
            -New $jar `
            -Old $BaselineJar `
            -BonferroniM $Deltas.Count `
            -Tag "phase13-c1-delta${delta}"

        $results[$delta] = "COMPLETED"
    } catch {
        $results[$delta] = "FAILED: $_"
        Write-Warning "C-1 delta=$delta failed: $_"
    } finally {
        # Always restore original constant
        $restored = Get-Content $SearcherFile -Raw
        if ($restored -match [regex]::Escape($patchedLine)) {
            $restored = $restored -replace [regex]::Escape($patchedLine), $ExpectedOrig
            Set-Content $SearcherFile $restored -Encoding UTF8 -NoNewline
            Write-Host "[C-1] Restored ASPIRATION_INITIAL_DELTA_CP = $OriginalValue"
        }
        # Refresh for next iteration
        $currentContent = Get-Content $SearcherFile -Raw
    }
    Write-Host ""
}

Write-Host "=== C-1 SPRT Summary ==="
foreach ($delta in $Deltas) {
    Write-Host "  delta=$delta : $($results[$delta])"
}
Write-Host ""
Write-Host "Reminder: check SPRT results in tools/results/ and update DEV_ENTRIES.md."
Write-Host "C-6 SPRT (correction history) must also be run — use:"
Write-Host "  .\tools\sprt.ps1 -New engine-uci\target\engine-uci-${VersionSuffix}.jar -Old $BaselineJar -Tag phase13-c6-correction-history"
