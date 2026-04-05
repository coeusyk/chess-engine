<#
.SYNOPSIS
    SMP SPRT: Vex-NThread vs Vex-1Thread (same JAR).
    Tests whether Lazy SMP with N threads genuinely improves strength.
    H0=0, H1=50, alpha=0.05, beta=0.05, TC=5+0.05

.PARAMETER Jar
    Path to the engine JAR to test (both sides use the same JAR, different thread counts).

.PARAMETER Threads
    Number of helper threads for the multi-threaded side (default: 2).

.EXAMPLE
    .\tools\sprt_smp.ps1 -Jar engine-uci\target\engine-uci.jar -Threads 2
#>
param(
    [Parameter(Mandatory)][string]$Jar,
    [int]$Threads = 2
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Cutechess = $env:CUTECHESS
if (-not $Cutechess) { $Cutechess = (Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue)?.Source }
if (-not $Cutechess -or -not (Test-Path $Cutechess)) {
    Write-Error "cutechess-cli not found. Set `$env:CUTECHESS or add cutechess-cli.exe to PATH."
    exit 1
}

$Java = if ($env:JAVA) { $env:JAVA } else { 'java' }

$JarResolved = Resolve-Path $Jar -ErrorAction SilentlyContinue
if (-not $JarResolved) { Write-Error "Engine JAR not found: $Jar"; exit 1 }

$ResultsDir = Join-Path $PSScriptRoot 'results'
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }

$TS     = Get-Date -Format 'yyyyMMdd_HHmmss'
$PgnOut = Join-Path $ResultsDir "sprt_smp_${Threads}T_$TS.pgn"

Write-Host "SPRT SMP: Vex-${Threads}T vs Vex-1T  H0=0 H1=50 alpha=0.05 beta=0.05  TC=5+0.05"
Write-Host "JAR     : $($JarResolved.Path)"
Write-Host "PGN     : $PgnOut"
Write-Host ""

& $Cutechess `
    -engine "name=Vex-${Threads}T" "cmd=$Java" "arg=-jar" "arg=$($JarResolved.Path)" proto=uci "option.Threads=$Threads" `
    -engine "name=Vex-1T"          "cmd=$Java" "arg=-jar" "arg=$($JarResolved.Path)" proto=uci "option.Threads=1" `
    -each tc=5+0.05 `
    -games 20000 `
    -repeat `
    -recover `
    -resign movecount=5 score=600 `
    -draw movenumber=40 movecount=8 score=10 `
    -sprt elo0=0 elo1=50 alpha=0.05 beta=0.05 `
    -concurrency 1 `
    -pgnout $PgnOut

Write-Host ""
Write-Host "PGN saved to: $PgnOut"
