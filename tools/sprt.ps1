<#
.SYNOPSIS
    Run an SPRT match between two engine JARs.

.DESCRIPTION
    Runs SPRT(ELO0, ELO1, ALPHA, BETA) to validate whether the new engine
    is stronger than the old engine.

    SPRT Parameters (edit as needed):
      ELO0  - null hypothesis Elo bound  (default 0:  no improvement)
      ELO1  - alternative hypothesis Elo bound (default 50: meaningful gain)
      ALPHA - false positive rate  (default 0.05 = 5%)
      BETA  - false negative rate  (default 0.05 = 5%)

    Reading Results (from cutechess-cli output):
      "H1 accepted" (LLR >= upper bound) - patch improves strength, merge
      "H0 accepted" (LLR <= lower bound) - no improvement detected, investigate

.PARAMETER New
    Path to the new engine JAR to test.

.PARAMETER Old
    Path to the old (baseline) engine JAR to test against.

.EXAMPLE
    .\tools\sprt.ps1 -New engine-uci\target\engine-uci.jar -Old tools\engine-uci-0.4.9.jar
#>
param(
    [Parameter(Mandatory)][string]$New,
    [Parameter(Mandatory)][string]$Old
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# --- SPRT parameters (edit here) ---
$Elo0     = 0
$Elo1     = 50
$Alpha    = 0.05
$Beta     = 0.05
$MaxGames = 20000
$TC       = '5+0.05'

# --- Resolve cutechess-cli from env or PATH ---
$Cutechess = $env:CUTECHESS
if (-not $Cutechess) { $Cutechess = (Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue)?.Source }
if (-not $Cutechess -or -not (Test-Path $Cutechess)) {
    Write-Error "cutechess-cli not found. Set `$env:CUTECHESS or add cutechess-cli.exe to PATH."
    exit 1
}

$Java = if ($env:JAVA) { $env:JAVA } else { 'java' }

# --- Resolve JAR paths relative to caller ---
$NewResolved = Resolve-Path $New -ErrorAction SilentlyContinue
$OldResolved = Resolve-Path $Old -ErrorAction SilentlyContinue

if (-not $NewResolved) { Write-Error "New engine JAR not found: $New"; exit 1 }
if (-not $OldResolved) { Write-Error "Old engine JAR not found: $Old"; exit 1 }

$ResultsDir = Join-Path $PSScriptRoot 'results'
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }

$TS     = Get-Date -Format 'yyyyMMdd_HHmmss'
$PgnOut = Join-Path $ResultsDir "sprt_$TS.pgn"

Write-Host "SPRT: new vs old  ELO0=$Elo0 ELO1=$Elo1 alpha=$Alpha beta=$Beta  TC=$TC"
Write-Host "NEW : $($NewResolved.Path)"
Write-Host "OLD : $($OldResolved.Path)"
Write-Host "PGN : $PgnOut"
Write-Host ""

& $Cutechess `
    -engine "name=Vex-new" "cmd=$Java" "arg=-jar" "arg=$($NewResolved.Path)" proto=uci `
    -engine "name=Vex-old" "cmd=$Java" "arg=-jar" "arg=$($OldResolved.Path)" proto=uci `
    -each tc=$TC `
    -games $MaxGames `
    -repeat `
    -recover `
    -resign movecount=5 score=600 `
    -draw movenumber=40 movecount=8 score=10 `
    -sprt "elo0=$Elo0" "elo1=$Elo1" "alpha=$Alpha" "beta=$Beta" `
    -concurrency 2 `
    -ratinginterval 10 `
    -pgnout $PgnOut

Write-Host ""
Write-Host "PGN saved to: $PgnOut"
