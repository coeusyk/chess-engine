<#
.SYNOPSIS
    Phase 9B SPRT: latest engine-uci JAR (Phase 9B search improvements) vs Phase 9A baseline.
    H0=0, H1=10, alpha=0.05, beta=0.05, TC=5+0.05

.DESCRIPTION
    Validates that the combined Phase 9B search improvements (TT aging, LMR log2 formula,
    null-move threshold fix, futility margin 150 cp, aspiration depth >= 4, and pawn-hash
    CI gate) do not regress strength and ideally improve Elo vs the Phase 9A baseline.

    Changes tested in this SPRT batch:
      - TT aging: evict entries older than AGE_THRESHOLD=4 generations (Issue #111)
      - Null-move threshold: depth > 6 for R=3 (was >= 6) (Issue #112)
      - LMR: moveIndex >= 4 + log2-based formula  (Issue #113)
      - Futility margin depth 1: raised 100 -> 150 cp (Issue #114)
      - Aspiration windows: activate at depth >= 4 (was >= 2) (Issue #116)

    Baseline: engine-uci-0.4.9.jar (Phase 9A with Lazy SMP + TimeManager + gen-bump fix)

    SPRT parameters: H0=0, H1=10, alpha=0.05, beta=0.05
    Use H1=10 rather than H1=50 because individual search tweaks typically gain <10 Elo.

.EXAMPLE
    # Build first:
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
    cd chess-engine
    .\mvnw -pl engine-uci -am package -DskipTests

    # Then run SPRT:
    .\tools\sprt_phase9b.ps1
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Cutechess = $env:CUTECHESS
if (-not $Cutechess) {
    $cmd = Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue
    if ($cmd) { $Cutechess = $cmd.Source }
}
if (-not $Cutechess -or -not (Test-Path $Cutechess)) {
    Write-Error "cutechess-cli not found. Set `$env:CUTECHESS or add cutechess-cli.exe to PATH."
    exit 1
}

$Java = if ($env:JAVA) { $env:JAVA } else { 'java' }

$TargetDir = Join-Path (Join-Path (Join-Path $PSScriptRoot '..') 'engine-uci') 'target'
$NewJar    = Get-ChildItem -Path $TargetDir -Filter 'engine-uci-*.jar' -ErrorAction SilentlyContinue |
             Where-Object { $_.Name -notmatch '^original-' } |
             Sort-Object LastWriteTime -Descending |
             Select-Object -First 1

if (-not $NewJar) {
    Write-Error "No engine-uci JAR found in $TargetDir.`nRun: .\mvnw.cmd -pl engine-uci -am package -DskipTests"
    exit 1
}

$OldJar = Join-Path $PSScriptRoot 'engine-uci-0.4.9.jar'
if (-not (Test-Path $OldJar)) { Write-Error "Phase 9A baseline JAR not found: $OldJar"; exit 1 }

$ResultsDir = Join-Path $PSScriptRoot 'results'
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }

$TS     = Get-Date -Format 'yyyyMMdd_HHmmss'
$PgnOut = Join-Path $ResultsDir "sprt_phase9b_$TS.pgn"

Write-Host "SPRT Phase 9B: latest vs engine-uci-0.4.9  H0=0 H1=10 alpha=0.05 beta=0.05  TC=5+0.05"
Write-Host "NEW : $($NewJar.FullName)"
Write-Host "OLD : $OldJar"
Write-Host "PGN : $PgnOut"
Write-Host ""

& $Cutechess `
    -engine "name=Vex-9B" "cmd=$Java" "arg=-jar" "arg=$($NewJar.FullName)" proto=uci `
    -engine "name=Vex-9A" "cmd=$Java" "arg=-jar" "arg=$OldJar" proto=uci `
    -each tc=5+0.05 `
    -games 20000 `
    -repeat `
    -recover `
    -resign movecount=5 score=600 `
    -draw movenumber=40 movecount=8 score=10 `
    -sprt elo0=0 elo1=10 alpha=0.05 beta=0.05 `
    -concurrency 2 `
    -ratinginterval 10 `
    -pgnout $PgnOut

Write-Host ""
Write-Host "PGN saved to: $PgnOut"
