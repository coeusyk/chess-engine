<#
.SYNOPSIS
    Run a SPRT test between two engine JARs using cutechess-cli.

.DESCRIPTION
    Runs a Sequential Probability Ratio Test between a new and old engine JAR.
    Supports Bonferroni correction for multiple comparisons.
    Results and PGN are written to tools/results/.

    Run from the chess-engine/ directory:
        .\tools\sprt.ps1 -New <newJar> -Old <oldJar> -Tag <tag>

.PARAMETER New
    Path to the new (candidate) engine JAR.

.PARAMETER Old
    Path to the old (baseline) engine JAR.

.PARAMETER Tag
    Short name used in output file names (e.g., "phase13-b1-kingsafety").

.PARAMETER BonferroniM
    Number of simultaneous hypotheses (default 1 = no correction).
    Sets alpha = beta = 0.05 / BonferroniM.

.PARAMETER Elo0
    H0 Elo difference (default 0).

.PARAMETER Elo1
    H1 Elo difference (default 10).

.PARAMETER TC
    Time control string passed to cutechess-cli (default '100+1').

.PARAMETER Concurrency
    Number of games to run in parallel (default 2).

.PARAMETER EngineThreads
    Number of threads per engine instance (default 1).

.PARAMETER MinGames
    Minimum game count before SPRT can stop (default 0 = no minimum).

.PARAMETER OpeningsFile
    Path to an EPD opening book. Auto-detected from tools/noob_3moves.epd if empty.
#>
param(
    [Parameter(Mandatory)][string]$New,
    [Parameter(Mandatory)][string]$Old,
    [string]$Tag          = "sprt",
    [int]   $BonferroniM  = 1,
    [double]$Elo0         = 0,
    [double]$Elo1         = 10,
    [string]$TC           = "100+1",
    [int]   $Concurrency  = 2,
    [int]   $EngineThreads = 1,
    [int]   $MinGames     = 0,
    [string]$OpeningsFile = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ─── Locate cutechess-cli ────────────────────────────────────────────────────
$Cutechess = $env:CUTECHESS
if (-not $Cutechess) {
    $Cutechess = (Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue)?.Source
}
if (-not $Cutechess -or -not (Test-Path $Cutechess)) {
    Write-Error "cutechess-cli not found. Set `$env:CUTECHESS or add cutechess-cli.exe to PATH."
    exit 1
}

# ─── Locate Java ────────────────────────────────────────────────────────────
$Java = if ($env:JAVA) {
    Join-Path $env:JAVA 'bin\java.exe'
} elseif ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    'java'
}

# ─── Resolve JARs ────────────────────────────────────────────────────────────
$NewResolved = Resolve-Path $New -ErrorAction SilentlyContinue
if (-not $NewResolved) { Write-Error "New engine JAR not found: $New"; exit 1 }
$OldResolved = Resolve-Path $Old -ErrorAction SilentlyContinue
if (-not $OldResolved) { Write-Error "Old engine JAR not found: $Old"; exit 1 }

# ─── SPRT alpha / beta (Bonferroni correction) ───────────────────────────────
$alpha = 0.05 / $BonferroniM
$beta  = 0.05 / $BonferroniM

# ─── Opening book ────────────────────────────────────────────────────────────
if ($OpeningsFile -eq "") {
    $defaultBook = Join-Path $PSScriptRoot 'noob_3moves.epd'
    if (Test-Path $defaultBook) { $OpeningsFile = $defaultBook }
}
$openingsArgs = @()
if ($OpeningsFile -ne "" -and (Test-Path $OpeningsFile)) {
    $openingsArgs = @("-openings", "file=$OpeningsFile", "format=epd", "order=random", "plies=4")
}

# ─── Output paths ────────────────────────────────────────────────────────────
$ResultsDir = Join-Path $PSScriptRoot 'results'
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }

$TS     = Get-Date -Format 'yyyyMMdd_HHmmss'
$PgnOut = Join-Path $ResultsDir "sprt_${Tag}_${TS}.pgn"
$LogOut = Join-Path $ResultsDir "sprt_${Tag}_${TS}.log"

# ─── Summary header ──────────────────────────────────────────────────────────
Write-Host "SPRT: new vs old  ELO0=$Elo0 ELO1=$Elo1 alpha=$alpha beta=$beta  TC=$TC  concurrency=$Concurrency  threads/engine=$EngineThreads"
Write-Host "NEW : $($NewResolved.Path)"
Write-Host "OLD : $($OldResolved.Path)"
Write-Host "PGN : $PgnOut"
Write-Host "LOG : $LogOut"
if ($OpeningsFile -ne "" -and (Test-Path $OpeningsFile)) {
    Write-Host "Opening book: $OpeningsFile"
} else {
    Write-Host "Opening book: (none)"
}
if ($MinGames -gt 0) { Write-Host "Min games   : $MinGames" }
Write-Host ""

# ─── Build cutechess-cli arguments ───────────────────────────────────────────
$maxGames = if ($MinGames -gt 0) { [math]::Max($MinGames, 20000) } else { 20000 }

$ccArgs = @(
    "-engine", "name=NEW", "cmd=$Java", "arg=-jar", "arg=$($NewResolved.Path)", "proto=uci", "option.Threads=$EngineThreads",
    "-engine", "name=OLD", "cmd=$Java", "arg=-jar", "arg=$($OldResolved.Path)", "proto=uci", "option.Threads=$EngineThreads",
    "-each", "tc=$TC",
    "-games", "$maxGames",
    "-repeat",
    "-recover",
    "-resign", "movecount=5", "score=600",
    "-draw", "movenumber=40", "movecount=8", "score=10",
    "-sprt", "elo0=$Elo0", "elo1=$Elo1", "alpha=$alpha", "beta=$beta",
    "-concurrency", "$Concurrency",
    "-ratinginterval", "10",
    "-pgnout", $PgnOut
)

if ($openingsArgs.Count -gt 0) {
    $ccArgs += $openingsArgs
}

# ─── Run cutechess-cli ───────────────────────────────────────────────────────
& $Cutechess @ccArgs 2>&1 | Tee-Object -FilePath $LogOut

Write-Host ""
Write-Host "SPRT complete. Log: $LogOut"
