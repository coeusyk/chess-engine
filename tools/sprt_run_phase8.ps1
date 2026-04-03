<#
.SYNOPSIS
    Phase 8 SPRT: latest engine-uci JAR vs pre-tuning-0.4.8 baseline.
    H0=0, H1=50, alpha=0.05, beta=0.05, TC=10+0.1

.DESCRIPTION
    Picks the most recently built non-shaded engine-uci JAR from engine-uci/target/
    and runs a full SPRT against the pre-Phase-8 baseline (pre-tuning-0.4.8.jar).
    Use this to validate the full Phase 8 batch against the starting point.
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Cutechess = $env:CUTECHESS
if (-not $Cutechess) { $Cutechess = (Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue)?.Source }
if (-not $Cutechess -or -not (Test-Path $Cutechess)) {
    Write-Error "cutechess-cli not found. Set `$env:CUTECHESS or add cutechess-cli.exe to PATH."
    exit 1
}

$Java = if ($env:JAVA) { $env:JAVA } else { 'java' }

$TargetDir = Join-Path $PSScriptRoot '..' 'engine-uci' 'target'
$NewJar    = Get-ChildItem -Path $TargetDir -Filter 'engine-uci-*.jar' -ErrorAction SilentlyContinue |
             Where-Object { $_.Name -notmatch '^original-' } |
             Sort-Object LastWriteTime -Descending |
             Select-Object -First 1

if (-not $NewJar) {
    Write-Error "No engine-uci JAR found in $TargetDir.`nRun: .\mvnw.cmd -pl engine-uci -am package -DskipTests"
    exit 1
}

$OldJar = Join-Path $PSScriptRoot 'pre-tuning-0.4.8.jar'
if (-not (Test-Path $OldJar)) { Write-Error "Baseline JAR not found: $OldJar"; exit 1 }

$ResultsDir = Join-Path $PSScriptRoot 'results'
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }

$TS     = Get-Date -Format 'yyyyMMdd_HHmmss'
$PgnOut = Join-Path $ResultsDir "sprt_phase8_$TS.pgn"

Write-Host "SPRT: Phase 8 latest vs pre-tuning-0.4.8  H0=0 H1=50 alpha=0.05 beta=0.05  TC=10+0.1"
Write-Host "NEW : $($NewJar.FullName)"
Write-Host "OLD : $OldJar"
Write-Host "PGN : $PgnOut"
Write-Host ""

& $Cutechess `
    -engine "name=Vex-new" "cmd=$Java" "arg=-jar" "arg=$($NewJar.FullName)" proto=uci `
    -engine "name=Vex-old" "cmd=$Java" "arg=-jar" "arg=$OldJar" proto=uci `
    -each tc=10+0.1 `
    -games 20000 `
    -repeat `
    -recover `
    -resign movecount=5 score=600 `
    -draw movenumber=40 movecount=8 score=10 `
    -sprt elo0=0 elo1=50 alpha=0.05 beta=0.05 `
    -concurrency 2 `
    -pgnout $PgnOut

Write-Host ""
Write-Host "PGN saved to: $PgnOut"
