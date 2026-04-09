<#
.SYNOPSIS
    Run an SPRT match between two engine JARs.

.DESCRIPTION
    Runs SPRT(ELO0, ELO1, ALPHA, BETA) to validate whether the new engine
    is stronger than the old engine.

    SPRT Parameters:
      ELO0  - null hypothesis Elo bound  (default 0:  no improvement)
      ELO1  - alternative hypothesis Elo bound (default 50: meaningful gain;
              use -Elo1 5 for tight tuner-methodology validations per AC)
      ALPHA - false positive rate  (default 0.05 = 5%)
      BETA  - false negative rate  (default 0.05 = 5%)

    Reading Results (from cutechess-cli output):
      "H1 accepted" (LLR >= upper bound) - patch improves strength, merge
      "H0 accepted" (LLR <= lower bound) - no improvement detected, investigate

.PARAMETER New
    Path to the new engine JAR to test.

.PARAMETER Old
    Path to the old (baseline) engine JAR to test against.

.PARAMETER BonferroniM
    Number of simultaneous hypotheses (for Bonferroni family-wise error correction).
    Default: 0 (no correction). When set > 1, alpha and beta are divided by this value.
    Example: -BonferroniM 5 adjusts alpha=0.05 to per-test alpha=0.01.

.PARAMETER Elo0
    Null hypothesis Elo bound (default: 0). Override per-issue AC as needed.

.PARAMETER Elo1
    Alternative hypothesis Elo bound (default: 50). Use -Elo1 5 for tuning-methodology SPRTs.

.PARAMETER OpeningsFile
    Path to an EPD opening book. Defaults to tools/noob_3moves.epd if the file is present
    next to the script. Pass an empty string ("") to explicitly disable.

.EXAMPLE
    .\tools\sprt.ps1 -New engine-uci\target\engine-uci.jar -Old tools\engine-uci-0.4.9.jar
.EXAMPLE
    .\tools\sprt.ps1 -New engine-uci\target\engine-uci.jar -Old tools\engine-uci-0.5.5.jar -Elo1 5
.EXAMPLE
    .\tools\sprt.ps1 -New engine-uci\target\engine-uci.jar -Old tools\engine-uci-0.4.9.jar -BonferroniM 5
#>
param(
    [Parameter(Mandatory)][string]$New,
    [Parameter(Mandatory)][string]$Old,
    [int]$BonferroniM = 0,
    [int]$Elo0 = 0,
    [int]$Elo1 = 50,
    [string]$OpeningsFile = "",  # Default: auto-detect noob_3moves.epd next to script
    [string]$Tag = ""            # Optional descriptive tag for PGN filename (e.g. "phase13-material-group")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# --- SPRT parameters (override via -Elo0/-Elo1 on the command line) ---
$Alpha    = 0.05
$Beta     = 0.05
$MaxGames = 20000
$TC       = '5+0.05'

# --- Bonferroni family-wise error correction (#136) ---
if ($BonferroniM -gt 1) {
    $Alpha = $Alpha / $BonferroniM
    $Beta  = $Beta  / $BonferroniM
    Write-Host "Bonferroni correction applied: per-test alpha=$Alpha, beta=$Beta for m=$BonferroniM hypotheses."
}

# --- Resolve cutechess-cli from env or PATH ---
$Cutechess = $env:CUTECHESS
if (-not $Cutechess) { $Cutechess = (Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue)?.Source }
if (-not $Cutechess -or -not (Test-Path $Cutechess)) {
    Write-Error "cutechess-cli not found. Set `$env:CUTECHESS or add cutechess-cli.exe to PATH."
    exit 1
}

$Java = if ($env:JAVA) { $env:JAVA } else { 'java' }

# --- Resolve opening book (auto-detect noob_3moves.epd if not specified) ---
if ($OpeningsFile -eq "") {
    $defaultBook = Join-Path $PSScriptRoot 'noob_3moves.epd'
    if (Test-Path $defaultBook) { $OpeningsFile = $defaultBook }
}
$openingsArgs = @()
if ($OpeningsFile -ne "" -and (Test-Path $OpeningsFile)) {
    $openingsArgs = @("-openings", "file=$OpeningsFile", "format=epd", "order=random", "plies=4")
    Write-Host "Opening book: $OpeningsFile"
} elseif ($OpeningsFile -ne "") {
    Write-Warning "Opening book not found: $OpeningsFile — running without openings"
}

# --- Resolve JAR paths relative to caller ---
$NewResolved = Resolve-Path $New -ErrorAction SilentlyContinue
$OldResolved = Resolve-Path $Old -ErrorAction SilentlyContinue

if (-not $NewResolved) { Write-Error "New engine JAR not found: $New"; exit 1 }
if (-not $OldResolved) { Write-Error "Old engine JAR not found: $Old"; exit 1 }

$ResultsDir = Join-Path $PSScriptRoot 'results'
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }

$TS      = Get-Date -Format 'yyyyMMdd_HHmmss'
$tagPart = if ($Tag) { "${Tag}_" } else { "" }
$PgnOut  = Join-Path $ResultsDir "sprt_${tagPart}$TS.pgn"

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
    @openingsArgs `
    -pgnout $PgnOut

Write-Host ""
Write-Host "PGN saved to: $PgnOut"
