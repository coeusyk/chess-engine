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
    Time control string passed to cutechess-cli (default '60+0.6').

.PARAMETER Concurrency
    Number of games to run in parallel (default 2).

.PARAMETER EngineThreads
    Number of threads per engine instance (default 1).

.PARAMETER MinGames
    Minimum game count before SPRT can stop (default 0 = no minimum).

.PARAMETER OpeningsFile
    Path to an EPD opening book. Auto-detected from tools/noob_3moves.epd if empty.

.PARAMETER NewOptions
    Extra UCI options for the New engine only, as "Name=Value" strings (e.g.
    "EvalType=NNUE","EvalFile=C:\path\net.nnue"). Default: none -- every existing
    invocation of this script is unaffected. Added for issue #205 (E-5), which
    needs the same JAR loaded with two different EvalType configs; sprt.ps1 had
    no way to express a per-engine option beyond the existing Threads plumbing.

.PARAMETER OldOptions
    Extra UCI options for the Old engine only, as "Name=Value" strings. Default: none.
#>
param(
    [Parameter(Mandatory)][string]$New,
    [Parameter(Mandatory)][string]$Old,
    [string]$Tag          = "sprt",
    [int]   $BonferroniM  = 1,
    [double]$Alpha        = 0.05,
    [double]$Beta         = 0.05,
    [double]$Elo0         = 0,
    [double]$Elo1         = 10,
    [string]$TC           = "60+0.6",
    [int]   $Concurrency  = 2,
    [int]   $EngineThreads = 1,
    [int]   $MinGames     = 0,
    [int]   $MaxGames     = 0,
    [string]$OpeningsFile = "",
    [string[]]$NewOptions = @(),
    [string[]]$OldOptions = @()
)

# ─── Color-balance warning thresholds (issue #213) ───────────────────────────
# Informational only: reports the White/Black score split periodically so a
# persistent color asymmetry (as investigated in #213) is visible during a
# run instead of only in a post-hoc PGN audit. Never affects SPRT stopping.
$ColorCheckMinGames    = 400
$ColorCheckIntervalGames = 200

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ─── Locate cutechess-cli ────────────────────────────────────────────────────
$Cutechess = $env:CUTECHESS
if (-not $Cutechess) {
    $cmd = Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue
    $Cutechess = if ($cmd) { $cmd.Source } else { $null }
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
$alpha = $Alpha / $BonferroniM
$beta  = $Beta  / $BonferroniM

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
Write-Host "SPRT: new vs old  ELO0=$Elo0 ELO1=$Elo1 alpha=$alpha beta=$beta  TC=$TC  concurrency=$Concurrency  threads/engine=$EngineThreads$(if ($MaxGames -gt 0) { "  maxGames=$MaxGames" })"
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
$maxGames = if ($MaxGames -gt 0) { $MaxGames } elseif ($MinGames -gt 0) { [math]::Max($MinGames, 20000) } else { 20000 }

$newOptionArgs = @($NewOptions | ForEach-Object { "option.$_" })
$oldOptionArgs = @($OldOptions | ForEach-Object { "option.$_" })

$ccArgs = @("-engine", "name=NEW", "cmd=$Java", "arg=-jar", "arg=$($NewResolved.Path)", "proto=uci", "option.Threads=$EngineThreads")
$ccArgs += $newOptionArgs
$ccArgs += @("-engine", "name=OLD", "cmd=$Java", "arg=-jar", "arg=$($OldResolved.Path)", "proto=uci", "option.Threads=$EngineThreads")
$ccArgs += $oldOptionArgs
$ccArgs += @(
    "-each", "tc=$TC",
    "-games", "$maxGames",
    "-repeat",
    "-recover",
    "-resign", "movecount=5", "score=400",
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
# Parses cutechess-cli's own "Finished game N (White vs Black): result" lines as they
# stream by (format confirmed against real SPRT logs in tools/results/*.log) to tally a
# running White/Black score split. This mirrors -- and never touches -- the existing
# -sprt stopping logic: it only ever prints an extra informational line.
$whiteWins = 0
$whiteLosses = 0
$draws = 0
$nextColorCheckpoint = $ColorCheckMinGames

$logWriter = New-Object System.IO.StreamWriter($LogOut, $false)
try {
    & $Cutechess @ccArgs 2>&1 | ForEach-Object {
        $line = $_
        Write-Host $line
        $logWriter.WriteLine($line)

        if ($line -match '^Finished game \d+ \(\S+ vs \S+\): (\S+)') {
            $result = $Matches[1]
            # Tally only recognised result strings, and derive the game count from the
            # tallies themselves (rather than an independent counter) so an unexpected
            # cutechess-cli result string can never silently desync numerator/denominator.
            switch ($result) {
                '1-0'     { $whiteWins++ }
                '0-1'     { $whiteLosses++ }
                '1/2-1/2' { $draws++ }
            }
            $completedGames = $whiteWins + $whiteLosses + $draws

            if ($completedGames -ge $nextColorCheckpoint) {
                $n = $completedGames
                $whiteScore = ($whiteWins + 0.5 * $draws) / $n
                $blackScore = 1 - $whiteScore

                # Empirical (not assumed-binomial) per-game variance from the actual W/L/D
                # counts -- same methodology as the #213 investigation doc.
                $varWin  = $whiteWins   * [Math]::Pow((1   - $whiteScore), 2)
                $varDraw = $draws       * [Math]::Pow((0.5 - $whiteScore), 2)
                $varLoss = $whiteLosses * [Math]::Pow((0   - $whiteScore), 2)
                $variance = ($varWin + $varDraw + $varLoss) / ($n - 1)
                $se = [Math]::Sqrt($variance / $n)

                $z = 0
                if ($se -gt 0) { $z = ($whiteScore - 0.5) / $se }
                $ciLow  = $whiteScore - 1.96 * $se
                $ciHigh = $whiteScore + 1.96 * $se

                $msg = "[color-balance check @ $n games] White={0:P1}  Black={1:P1}  95% CI=[{2:P1}, {3:P1}]  z={4:F3}  (informational only -- issue #213, does not affect SPRT stopping)" -f $whiteScore, $blackScore, $ciLow, $ciHigh, $z
                Write-Host ""
                Write-Host $msg
                Write-Host ""

                $nextColorCheckpoint += $ColorCheckIntervalGames
            }
        }
    }
} finally {
    $logWriter.Flush()
    $logWriter.Close()
}

Write-Host ""
Write-Host "SPRT complete. Log: $LogOut"
