<#
.SYNOPSIS
    SPRT: 9baf527 (bounded Q-search check extension) vs Vex-0.4.9
    H0=0, H1=50, alpha=0.05, beta=0.05, TC=10+0.1
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

$OldJar = Join-Path $PSScriptRoot 'engine-uci-0.4.9.jar'
if (-not (Test-Path $OldJar)) { Write-Error "Baseline JAR not found: $OldJar"; exit 1 }

$ResultsDir = Join-Path $PSScriptRoot 'results'
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }

$TS     = Get-Date -Format 'yyyyMMdd_HHmmss'
$PgnOut = Join-Path $ResultsDir "sprt_9baf527_$TS.pgn"

Write-Host "SPRT: 9baf527 vs 0.4.9  H0=0 H1=50 alpha=0.05 beta=0.05  TC=10+0.1"
Write-Host "NEW : $($NewJar.FullName)"
Write-Host "OLD : $OldJar"
Write-Host "PGN : $PgnOut"
Write-Host ""

& $Cutechess `
    -engine "name=Vex-new" "cmd=$Java" "arg=-jar" "arg=$($NewJar.FullName)" proto=uci `
    -engine "name=Vex-old" "cmd=$Java" "arg=-jar" "arg=$OldJar" proto=uci `
    -each tc=10+0.1 `
    -rounds 200 -games 2 `
    -repeat `
    -recover `
    -concurrency 2 `
    -sprt elo0=0 elo1=50 alpha=0.05 beta=0.05 `
    -ratinginterval 10 `
    -pgnout $PgnOut

Write-Host ""
Write-Host "PGN saved to: $PgnOut"
