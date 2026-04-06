#Requires -Version 5.1
<#
.SYNOPSIS
    Self-play stability batch for the Vex chess engine.

.DESCRIPTION
    Runs the engine against itself for N games using cutechess-cli and checks for:
      - Crashes (engine process dying unexpectedly)
      - Time forfeits
      - Adjudicated illegal moves

    Two-tier CCRL pre-submission stability gate:

    Tier 1 — crash/illegal-move gate (run first, ~30 min):
        .\tools\selfplay_batch.ps1 -Games 200 -TC "10+0.1" -Concurrency 2

    Tier 2 — CCRL TC time-forfeit check (run overnight, concurrency=1 matches CCRL hardware):
        .\tools\selfplay_batch.ps1 -Games 50 -TC "40/240" -Concurrency 1
        (CCRL tests at 40 moves / 4 minutes = 240 seconds in cutechess TC format)

    NOTE: TC values are passed directly to cutechess-cli and are in SECONDS.
          CCRL 40/4 (40 moves in 4 minutes) = -TC "40/240" here.
          Concurrency=1 is required for Tier 2 to match CCRL single-game-at-a-time conditions.

.PARAMETER Games
    Number of games to play (default: 200).

.PARAMETER TC
    Time control string passed to cutechess-cli in seconds (default: "10+0.1").
    CCRL 40/4 (4 minutes) = "40/240". Do NOT use "40/4" — that is 4 seconds.

.PARAMETER Concurrency
    Number of concurrent games (default: 1). Use 2 for Tier 1, 1 for Tier 2 (CCRL conditions).

.PARAMETER JarPath
    Explicit path to the engine-uci fat JAR. If omitted the script auto-detects the
    newest engine-uci-*.jar (excluding original-*) in engine-uci/target/.
#>
param(
    [int]    $Games       = 200,
    [string] $TC          = "10+0.1",
    [int]    $Concurrency = 1,
    [string] $JarPath     = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$AnyFailed = $false

function Pass { param([string]$Label)
    Write-Host "  [PASS] $Label" -ForegroundColor Green
}
function Fail {
    param([string]$Label, [string]$Detail = "")
    if ($Detail) {
        Write-Host "  [FAIL] $Label - $Detail" -ForegroundColor Red
    } else {
        Write-Host "  [FAIL] $Label" -ForegroundColor Red
    }
    $Script:AnyFailed = $true
}

# ---------------------------------------------------------------------------
# Resolve cutechess-cli
# ---------------------------------------------------------------------------
$Cutechess = $env:CUTECHESS
if (-not $Cutechess) {
    $cc = Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue
    if ($cc) { $Cutechess = $cc.Source }
}
if (-not $Cutechess -or -not (Test-Path $Cutechess)) {
    Write-Error 'cutechess-cli not found. Set $env:CUTECHESS or add cutechess-cli.exe to PATH.'
    exit 1
}

# Resolve java: check JAVA_HOME exists, then JAVA env, then PATH
$Java = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path (Join-Path $env:JAVA_HOME 'bin') 'java.exe'
    if (Test-Path $candidate) { $Java = $candidate }
}
if (-not $Java -and $env:JAVA) { $Java = $env:JAVA }
if (-not $Java) {
    $javaCmd = Get-Command 'java' -ErrorAction SilentlyContinue
    if ($javaCmd) { $Java = $javaCmd.Source }
}
if (-not $Java) { $Java = 'java' }

# ---------------------------------------------------------------------------
# Resolve JAR
# ---------------------------------------------------------------------------
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $JarPath) {
    $targetDir  = Join-Path (Join-Path $repoRoot "engine-uci") "target"
    $candidates = @(Get-ChildItem -Path $targetDir -Filter "engine-uci-*.jar" -ErrorAction SilentlyContinue |
                  Where-Object { $_.Name -notlike "original-*" } |
                  Sort-Object LastWriteTime -Descending)
    if ($candidates.Count -gt 0) { $JarPath = $candidates[0].FullName }
}
if (-not $JarPath -or -not (Test-Path $JarPath)) {
    Write-Error "engine-uci fat JAR not found. Run: mvn package -pl engine-uci -am"
    exit 1
}

$JarName = Split-Path -Leaf $JarPath

# ---------------------------------------------------------------------------
# Setup PGN output path
# ---------------------------------------------------------------------------
$ResultsDir = Join-Path $PSScriptRoot "results"
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }
$TS     = Get-Date -Format "yyyyMMdd_HHmmss"
$PgnOut = Join-Path $ResultsDir "selfplay_$TS.pgn"

Write-Host ""
Write-Host "Vex - Self-Play Stability Batch" -ForegroundColor Cyan
Write-Host "================================" -ForegroundColor Cyan
Write-Host "JAR         : $JarName"
Write-Host "Games       : $Games"
Write-Host "TC          : $TC"
Write-Host "Concurrency : $Concurrency"
Write-Host "PGN output  : $PgnOut"
Write-Host ""

# ---------------------------------------------------------------------------
# Run cutechess-cli
# ---------------------------------------------------------------------------
$ccArgs = @(
    "-engine", "name=Vex-A", "cmd=$Java", "arg=-jar", "arg=$JarPath", "proto=uci",
    "-engine", "name=Vex-B", "cmd=$Java", "arg=-jar", "arg=$JarPath", "proto=uci",
    "-each", "tc=$TC",
    "-games", "$Games",
    "-repeat",
    "-recover",
    "-resign",   "movecount=5",  "score=900",
    "-draw",     "movenumber=40", "movecount=8", "score=10",
    "-pgnout",   $PgnOut,
    "-concurrency", "$Concurrency"
)

Write-Host "Launching cutechess-cli...`n"
$tmpLog  = Join-Path $env:TEMP "selfplay_cc_$PID.log"
$oldEAP  = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $Cutechess @ccArgs 2>&1 | Tee-Object -FilePath $tmpLog
$ccExit  = $LASTEXITCODE
$ErrorActionPreference = $oldEAP
$ccOutput = if (Test-Path $tmpLog) { Get-Content $tmpLog } else { @() }
Remove-Item $tmpLog -ErrorAction SilentlyContinue

# ---------------------------------------------------------------------------
# Parse results
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Analysing results..." -ForegroundColor Cyan

$crashes      = @($ccOutput | Select-String -Pattern "disconnects|loses on time by disconnecting|forfeits").Count
$timeForfeits = @($ccOutput | Select-String -Pattern "loses on time|time forfeit").Count
$illegalMoves = @($ccOutput | Select-String -Pattern "illegal move|Illegal move").Count

# Parse final score line: "Score of Vex-A vs Vex-B: W - L - D  [score] N"
$scoreLine   = @($ccOutput | Select-String -Pattern "Score of") | Select-Object -Last 1
$gamesPlayed = if ($scoreLine -and $scoreLine.Line -match "\]\s*(\d+)\s*$") { [int]$Matches[1] } else { 0 }

Write-Host ""
Write-Host "Results:" -ForegroundColor Cyan
Write-Host "  Games played  : $gamesPlayed"
Write-Host "  Crashes       : $crashes"
Write-Host "  Time forfeits : $timeForfeits"
Write-Host "  Illegal moves : $illegalMoves"
Write-Host ""

if ($crashes -eq 0)      { Pass "Zero crashes" }       else { Fail "Zero crashes"       "count=$crashes" }
if ($timeForfeits -eq 0) { Pass "Zero time forfeits" } else { Fail "Zero time forfeits" "count=$timeForfeits" }
if ($illegalMoves -eq 0) { Pass "Zero illegal moves" } else { Fail "Zero illegal moves" "count=$illegalMoves" }

Write-Host ""
if ($AnyFailed) {
    Write-Host "RESULT: FAIL - stability issues detected" -ForegroundColor Red
    exit 1
} else {
    Write-Host "RESULT: PASS - $gamesPlayed games, 0 crashes, 0 time forfeits, 0 illegal moves" -ForegroundColor Green
    exit 0
}
