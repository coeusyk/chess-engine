<#
.SYNOPSIS
    Quick match between two engine JARs (no SPRT, just raw game results).

.PARAMETER Engine1
    Path to engine 1 JAR (displayed as Vex-new).

.PARAMETER Engine2
    Path to engine 2 JAR (displayed as Vex-old).

.PARAMETER Games
    Number of games to play (default: 100).

.PARAMETER TC
    Time control string passed to cutechess-cli (default: 10+0.1).

.EXAMPLE
    .\tools\match.ps1 -Engine1 engine-uci\target\engine.jar -Engine2 tools\engine-uci-0.4.9.jar -Games 200
#>
param(
    [Parameter(Mandatory)][string]$Engine1,
    [Parameter(Mandatory)][string]$Engine2,
    [int]$Games = 100,
    [string]$TC = '10+0.1'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Cutechess = $env:CUTECHESS
if (-not $Cutechess) { $cmd = Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue; if ($cmd) { $Cutechess = $cmd.Source } }
if (-not $Cutechess -or -not (Test-Path $Cutechess)) {
    Write-Error "cutechess-cli not found. Set `$env:CUTECHESS or add cutechess-cli.exe to PATH."
    exit 1
}

$Java = if ($env:JAVA) { $env:JAVA } else { 'java' }

$E1 = Resolve-Path $Engine1 -ErrorAction SilentlyContinue
$E2 = Resolve-Path $Engine2 -ErrorAction SilentlyContinue
if (-not $E1) { Write-Error "Engine 1 JAR not found: $Engine1"; exit 1 }
if (-not $E2) { Write-Error "Engine 2 JAR not found: $Engine2"; exit 1 }

$ResultsDir = Join-Path $PSScriptRoot 'results'
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }

$TS     = Get-Date -Format 'yyyyMMdd_HHmmss'
$PgnOut = Join-Path $ResultsDir "match_$TS.pgn"

Write-Host "Match: Vex-new vs Vex-old  games=$Games  TC=$TC"
Write-Host "ENG1: $($E1.Path)"
Write-Host "ENG2: $($E2.Path)"
Write-Host "PGN : $PgnOut"
Write-Host ""

& $Cutechess `
    -engine "name=Vex-new" "cmd=$Java" "arg=-jar" "arg=$($E1.Path)" proto=uci `
    -engine "name=Vex-old" "cmd=$Java" "arg=-jar" "arg=$($E2.Path)" proto=uci `
    -each tc=$TC `
    -games $Games `
    -repeat `
    -recover `
    -resign movecount=5 score=600 `
    -draw movenumber=40 movecount=8 score=10 `
    -pgnout $PgnOut `
    -concurrency 2

Write-Host ""
Write-Host "PGN saved to: $PgnOut"
