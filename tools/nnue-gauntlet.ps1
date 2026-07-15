<#
.SYNOPSIS
    NNUE-mode gauntlet: one engine-uci JAR playing itself, one side configured
    with EvalType=NNUE + EvalFile=<net>, the other left at the default
    (EvalType=Classical) -- candidate net vs. classical baseline. Issue #204 (E-4).

.DESCRIPTION
    match.ps1 (unmodified, per issue #204's explicit non-scope) takes two JAR
    *paths* with no way to pass per-engine UCI setoptions, so it cannot express
    "same JAR, two different EvalType/EvalFile configs" on its own. This script
    is the "(or equivalent)" the issue's own Scope permits: identical cutechess-cli
    conventions (games/TC defaults, -repeat -recover -resign -draw -pgnout
    -concurrency) to match.ps1, just with per-engine `option.X=Y` flags added.

    No statistical significance testing here (that's E-5/sprt.ps1) -- this is
    raw game results only, exactly like match.ps1's own stated scope.

.PARAMETER Engine
    Path to the engine-uci JAR (same JAR used for both sides).

.PARAMETER NnueFile
    Path to the candidate .nnue file (E-3's exported network).

.PARAMETER Games
    Number of games to play (default: 100, matching match.ps1's default).

.PARAMETER TC
    Time control string passed to cutechess-cli (default: 10+0.1, matching match.ps1).

.EXAMPLE
    .\tools\nnue-gauntlet.ps1 -Engine engine-uci\target\engine-uci-0.5.8-SNAPSHOT.jar `
        -NnueFile trainer\outputs\nets\dfffd3da-7f8f-4fc9-92dc-b3873c97fb21.nnue
#>
param(
    [Parameter(Mandatory)][string]$Engine,
    [Parameter(Mandatory)][string]$NnueFile,
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

$EngineJar = Resolve-Path $Engine -ErrorAction SilentlyContinue
$NnuePath = Resolve-Path $NnueFile -ErrorAction SilentlyContinue
if (-not $EngineJar) { Write-Error "Engine JAR not found: $Engine"; exit 1 }
if (-not $NnuePath) { Write-Error "NNUE file not found: $NnueFile"; exit 1 }

$ResultsDir = Join-Path $PSScriptRoot 'results'
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }

$TS     = Get-Date -Format 'yyyyMMdd_HHmmss'
$PgnOut = Join-Path $ResultsDir "gauntlet_nnue_$TS.pgn"

Write-Host "Gauntlet: Vex-NNUE vs Vex-Classical  games=$Games  TC=$TC"
Write-Host "ENGINE : $($EngineJar.Path)"
Write-Host "NNUE   : $($NnuePath.Path)"
Write-Host "PGN    : $PgnOut"
Write-Host ""

& $Cutechess `
    -engine "name=Vex-NNUE" "cmd=$Java" "arg=-jar" "arg=$($EngineJar.Path)" proto=uci "option.EvalType=NNUE" "option.EvalFile=$($NnuePath.Path)" `
    -engine "name=Vex-Classical" "cmd=$Java" "arg=-jar" "arg=$($EngineJar.Path)" proto=uci "option.EvalType=Classical" `
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
