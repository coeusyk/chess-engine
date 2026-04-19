<#
.SYNOPSIS
    Build and run SPRT tests for C-6: Correction History Table Size & Update Weight.

.DESCRIPTION
    C-6a: CORRECTION_HISTORY_SIZE 1024 -> 4096 (memory-only, reduces pawn-hash aliasing)
    C-6b: Update weight formula: GRAIN/max(1,depth) -> min(GRAIN, depth*16)
          (weights deeper updates more heavily; current formula favours shallow nodes)
    C-6c: Both changes combined vs baseline

    SPRT settings per the Phase 13 spec:
      H0=0 Elo, H1=50 Elo, alpha=beta=0.025 (Bonferroni M=3, ±3.91 bounds)
      TC: 60+0.6 — Concurrency: 4 games, 2 threads/engine — Min 600 games

    Run from the chess-engine/ directory:
        .\tools\run_c6_sprt.ps1

.PARAMETER Candidates
    Which candidates to test. Default: 'a','b','c'

.PARAMETER SkipBuild
    If set, skips the Maven build and reuses JAR files already present in tools/.
#>
param(
    [string[]]$Candidates = @('a', 'b', 'c'),
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SearcherFile        = "engine-core\src\main\java\coeusyk\game\chess\core\search\Searcher.java"
$BaselineJar         = "tools\baseline-v0.5.6-pretune.jar"
$VersionSuffix       = "0.5.6-SNAPSHOT"

# Baseline values
$OrigSize            = 1024
$SizeLine            = '    private static final int CORRECTION_HISTORY_SIZE = '
$ExpectedSize        = "${SizeLine}${OrigSize};"

$OrigWeightLine      = '                int weight = CORRECTION_HISTORY_GRAIN / Math.max(1, effectiveDepth);'
$NewWeightLine       = '                int weight = Math.min(CORRECTION_HISTORY_GRAIN, effectiveDepth * 16);'

if (-not (Test-Path $SearcherFile)) {
    Write-Error "Searcher.java not found at '$SearcherFile'. Run from chess-engine/ directory."
    exit 1
}
if (-not (Test-Path $BaselineJar)) {
    Write-Error "Baseline JAR not found: $BaselineJar"
    exit 1
}

$currentContent = Get-Content $SearcherFile -Raw
if ($currentContent -notmatch [regex]::Escape($ExpectedSize)) {
    Write-Error "CORRECTION_HISTORY_SIZE not at expected value ($OrigSize). Restore Searcher.java before running."
    exit 1
}
if ($currentContent -notmatch [regex]::Escape($OrigWeightLine)) {
    Write-Error "Correction history weight formula not at expected baseline. Restore Searcher.java before running."
    exit 1
}

Write-Host "C-6 SPRT Experiment — Correction History Table Size & Update Weight"
Write-Host "----------------------------------------------------------------------"
Write-Host "C-6a: CORRECTION_HISTORY_SIZE 1024 -> 4096"
Write-Host "C-6b: weight = GRAIN/max(1,depth)  ->  min(GRAIN, depth*16)"
Write-Host "C-6c: Both changes combined"
Write-Host "Baseline JAR : $BaselineJar"
Write-Host "SPRT settings: H0=0/H1=50 Elo, BonferroniM=3, TC=60+0.6, conc=4, threads=2, minGames=600"
Write-Host ""

$results = @{}

foreach ($cand in $Candidates) {
    $candLower = $cand.ToLower()
    $Jar = "tools\engine-uci-c6-corrhist${candLower}.jar"

    Write-Host ""
    Write-Host "=== C-6$candLower ==="
    Write-Host ""

    try {
        if (-not $SkipBuild) {
            $patched = $currentContent

            if ($candLower -eq 'a' -or $candLower -eq 'c') {
                $patched = $patched -replace [regex]::Escape($ExpectedSize), "${SizeLine}4096;"
                Write-Host "[C-6$candLower] Patched CORRECTION_HISTORY_SIZE to 4096"
            }
            if ($candLower -eq 'b' -or $candLower -eq 'c') {
                $patched = $patched -replace [regex]::Escape($OrigWeightLine), $NewWeightLine
                Write-Host "[C-6$candLower] Patched correction history weight formula"
            }

            Set-Content $SearcherFile $patched -Encoding UTF8 -NoNewline

            Write-Host "[C-6$candLower] Building..."
            & .\mvnw.cmd package -pl engine-uci -am -DskipTests -q
            if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)" }

            $builtJar = "engine-uci\target\engine-uci-${VersionSuffix}.jar"
            if (-not (Test-Path $builtJar)) { throw "Built JAR not found at: $builtJar" }
            Copy-Item $builtJar $Jar -Force
            Write-Host "[C-6$candLower] JAR saved: $Jar"
        } else {
            if (-not (Test-Path $Jar)) { throw "SkipBuild set but JAR not found: $Jar" }
            Write-Host "[C-6$candLower] Reusing existing JAR: $Jar (SkipBuild)"
        }

        Write-Host "[C-6$candLower] Running SPRT..."
        & .\tools\sprt.ps1 `
            -New $Jar `
            -Old $BaselineJar `
            -Tag "phase13-c6-corrhist${candLower}" `
            -BonferroniM 3 `
            -Elo0 0 -Elo1 50 `
            -TC '60+0.6' `
            -Concurrency 4 `
            -EngineThreads 2 `
            -MinGames 600

        $results[$candLower] = "COMPLETED"
    } catch {
        Write-Warning "C-6$candLower failed: $_"
        $results[$candLower] = "FAILED: $_"
    } finally {
        # Always restore Searcher.java
        $restored = Get-Content $SearcherFile -Raw
        $needsWrite = $false

        if ($candLower -eq 'a' -or $candLower -eq 'c') {
            if ($restored -match [regex]::Escape("${SizeLine}4096;")) {
                $restored = $restored -replace [regex]::Escape("${SizeLine}4096;"), $ExpectedSize
                $needsWrite = $true
                Write-Host "[C-6$candLower] Restored CORRECTION_HISTORY_SIZE to $OrigSize"
            }
        }
        if ($candLower -eq 'b' -or $candLower -eq 'c') {
            if ($restored -match [regex]::Escape($NewWeightLine)) {
                $restored = $restored -replace [regex]::Escape($NewWeightLine), $OrigWeightLine
                $needsWrite = $true
                Write-Host "[C-6$candLower] Restored correction history weight formula"
            }
        }
        if ($needsWrite) {
            Set-Content $SearcherFile $restored -Encoding UTF8 -NoNewline
        }
        $currentContent = Get-Content $SearcherFile -Raw
    }
}

Write-Host ""
Write-Host "=== C-6 SPRT Summary ==="
foreach ($cand in $Candidates) {
    Write-Host "  C-6$($cand.ToLower()): $($results[$cand.ToLower()])"
}
Write-Host "========================="
