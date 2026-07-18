<#
.SYNOPSIS
    SPRT PGN post-processing pipeline (issue #181): mines SPRT/gauntlet PGNs for a
    Texel-corpus extension, a blunder diagnostic corpus, and opening coverage stats.

.DESCRIPTION
    Replays every game in every .pgn file under -PgnDir using the project's own,
    already-tested chess logic (Board/SanConverter, via the new
    coeusyk.game.chess.core.tools.PgnReplayer helper class -- SAN replay needs real
    chess rules, which is not something to re-implement in PowerShell), then:

    -Mode QuietCorpus   : draw-game, quiet (no capture/check for -MinQuietPlies plies),
                          Stockfish-verified-safe positions, appended in the exact
                          `<fen> c9 "<result>";` format already used by
                          tools/quiet-labeled.epd (drop-in compatible with the existing
                          Texel corpus and engine-tuner's PositionLoader).
    -Mode BlunderCorpus : positions immediately before a large Stockfish-measured eval
                          swing, with the FEN, SF eval before/after, the swing size, and
                          the game result -- for explainEval-style diagnostic review, not
                          for tuning.
    -Mode OpeningStats  : per-starting-position (an "opening" = one -openings book entry,
                          identified by its starting FEN) coverage: game count, average
                          result (White's perspective, 1/0.5/0), average game length.
    -Mode All           : runs all three.

    Stockfish is the only evaluation oracle used to decide what is quiet / a blunder /
    a forced mate (per #181: "Do NOT use Vex's own eval as the labeling oracle"). The
    `{score/depth Xs}` annotations already embedded in cutechess-cli's PGN output are
    Vex's own self-reported eval from the actual game -- they are read only as passthrough
    context in PgnReplayer's output and are never written into any corpus file here.

    A game that fails to replay (malformed/unrecognised SAN) is skipped by PgnReplayer
    itself and reported to this script's own summary; it does not stop the run.

.PARAMETER Mode
    QuietCorpus | BlunderCorpus | OpeningStats | All.

.PARAMETER PgnDir
    Directory containing SPRT/gauntlet PGN files (default: tools/results -- the actual
    convention this repo already uses; issue #181's own text says "sprt_logs/", which
    does not exist in this repo and is treated as stale).

.PARAMETER StockfishPath
    Path to a Stockfish UCI binary. Required unless -DryRun (a dry run still needs
    Stockfish to compute the stats it prints -- only the final file-write step is
    skipped).

.PARAMETER EngineJar
    Path to an engine-uci fat JAR (any built version) -- used only to invoke the
    PgnReplayer helper class via `java -cp`, not to play any moves itself.

.PARAMETER OutDir
    Output directory (default: tools/output/).

.PARAMETER SfDepth
    Stockfish search depth for every position evaluated (default: 16, per issue #181).

.PARAMETER BlunderThresholdCp
    Minimum centipawn swing (Stockfish, side-to-move-relative) to count as a blunder,
    and also the swing ceiling used to reject a would-be "quiet" position that sits
    immediately next to one (default: 150, per issue #181; reused for both roles
    deliberately -- a position adjacent to a blunder-sized swing is not quiet by the
    same definition that makes it a blunder).

.PARAMETER MinQuietPlies
    Consecutive prior plies (this one included) with no capture and no check required
    before a position counts as "quiet" (default: 4, per issue #181).

.PARAMETER MaxAbsEvalCp
    Skip positions whose Stockfish eval exceeds this in absolute value -- keeps the
    quiet corpus to roughly balanced positions (default: 500, per issue #181).

.PARAMETER DryRun
    Run the full pipeline and print the summary counts, but do not write any output
    file.

.EXAMPLE
    .\tools\sprt_pgn_pipeline.ps1 -Mode All -StockfishPath C:\stockfish\stockfish.exe `
        -EngineJar engine-uci\target\engine-uci-0.5.9-SNAPSHOT.jar -DryRun
#>
param(
    [Parameter(Mandatory)][ValidateSet('QuietCorpus', 'BlunderCorpus', 'OpeningStats', 'All')][string]$Mode,
    [string]$PgnDir = "",
    [string]$StockfishPath = "",
    [string]$EngineJar = "",
    [string]$OutDir = "",
    [int]$SfDepth = 16,
    [int]$BlunderThresholdCp = 150,
    [int]$MinQuietPlies = 4,
    [int]$MaxAbsEvalCp = 500,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PgnDir -eq "") { $PgnDir = Join-Path $PSScriptRoot 'results' }
if ($OutDir -eq "") { $OutDir = Join-Path $PSScriptRoot 'output' }
if (-not (Test-Path $PgnDir)) { Write-Error "PGN directory not found: $PgnDir"; exit 1 }
if ($StockfishPath -eq "" -or -not (Test-Path $StockfishPath)) {
    Write-Error "Stockfish binary not found. Pass -StockfishPath explicitly."
    exit 1
}
if ($EngineJar -eq "" -or -not (Test-Path $EngineJar)) {
    Write-Error "Engine JAR not found. Pass -EngineJar explicitly (any built engine-uci JAR)."
    exit 1
}
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

$Java = if ($env:JAVA) {
    Join-Path $env:JAVA 'bin\java.exe'
} elseif ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    'java'
}

# ─── Stockfish UCI driver (deterministic: Threads=1, fixed depth every call) ────────

function Start-StockfishEngine {
    param([string]$Path)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Path
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.CreateNoWindow = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $proc.StandardInput.WriteLine("uci")
    Wait-ForStockfishLine -Proc $proc -Marker "uciok" | Out-Null
    $proc.StandardInput.WriteLine("setoption name Threads value 1")
    $proc.StandardInput.WriteLine("isready")
    Wait-ForStockfishLine -Proc $proc -Marker "readyok" | Out-Null
    return $proc
}

function Wait-ForStockfishLine {
    param([System.Diagnostics.Process]$Proc, [string]$Marker)
    $lines = New-Object System.Collections.Generic.List[string]
    while ($true) {
        $line = $Proc.StandardOutput.ReadLine()
        if ($null -eq $line) { break }
        $lines.Add($line)
        if ($line.Contains($Marker)) { break }
    }
    return $lines
}

# Returns a hashtable @{ IsMate = $bool; WhiteCp = <int, White-relative> } or $null if
# Stockfish never reports a usable score (should not happen in practice). Normalised to
# White-relative (using the FEN's own side-to-move field) rather than left
# side-to-move-relative, because a caller comparing an eval "before" a move against the
# eval "after" it is comparing two positions with DIFFERENT sides to move -- leaving
# both side-to-move-relative would silently compare mismatched perspectives. Every
# caller below works in this one consistent frame instead.
function Get-StockfishEval {
    param([System.Diagnostics.Process]$Proc, [string]$Fen, [int]$Depth)

    $Proc.StandardInput.WriteLine("ucinewgame")
    $Proc.StandardInput.WriteLine("position fen $Fen")
    $Proc.StandardInput.WriteLine("go depth $Depth")
    $lines = Wait-ForStockfishLine -Proc $Proc -Marker "bestmove"

    $lastScoreCp = $null
    $lastScoreIsMate = $false
    foreach ($line in $lines) {
        if ($line -match 'score mate (-?\d+)') {
            $lastScoreIsMate = $true
            $lastScoreCp = $null
        } elseif ($line -match 'score cp (-?\d+)') {
            $lastScoreIsMate = $false
            $lastScoreCp = [int]$Matches[1]
        }
    }
    if ($lastScoreIsMate) { return @{ IsMate = $true; WhiteCp = 0 } }
    if ($null -eq $lastScoreCp) { return $null }

    $sideToMove = ($Fen -split ' ')[1]
    $whiteCp = $lastScoreCp
    if ($sideToMove -eq 'b') { $whiteCp = -1 * $lastScoreCp }
    return @{ IsMate = $false; WhiteCp = $whiteCp }
}

# ─── PgnReplayer invocation + parsing ────────────────────────────────────────

# Returns an array of game objects: @{ Index; White; Black; Result; StartFen; Plies }
# where Plies is an array of @{ Ply; San; FenBefore; FenAfter; IsCapture; IsCheck }.
# Also prints a one-line skip count for this file (PgnReplayer skips a whole game -- and
# reports why on stderr -- rather than crash on a malformed one).
function Invoke-PgnReplay {
    param([string]$PgnPath)

    $rawLines = & $Java -cp $EngineJar coeusyk.game.chess.core.tools.PgnReplayer $PgnPath 2>&1
    $games = New-Object System.Collections.Generic.List[object]
    $current = $null
    $skipCount = 0

    foreach ($line in $rawLines) {
        if ($line -match '^SKIP\t') {
            $skipCount++
            continue
        }
        $fields = $line -split "`t"
        if ($fields[0] -eq 'GAME') {
            if ($null -ne $current) { $games.Add($current) }
            $current = [PSCustomObject]@{
                Index    = [int]$fields[1]
                White    = $fields[2]
                Black    = $fields[3]
                Result   = $fields[4]
                StartFen = $fields[5]
                Plies    = New-Object System.Collections.Generic.List[object]
            }
        } elseif ($fields[0] -eq 'PLY' -and $null -ne $current) {
            $current.Plies.Add([PSCustomObject]@{
                Ply        = [int]$fields[2]
                San        = $fields[3]
                FenBefore  = $fields[4]
                FenAfter   = $fields[5]
                IsCapture  = [bool]::Parse($fields[6])
                IsCheck    = [bool]::Parse($fields[7])
            })
        }
    }
    if ($null -ne $current) { $games.Add($current) }
    if ($skipCount -gt 0) { Write-Host "  ($skipCount game(s) skipped -- malformed/unrecognised SAN)" }
    return $games
}

# ─── Load all games from every PGN in -PgnDir ────────────────────────────────

$pgnFiles = Get-ChildItem -Path $PgnDir -Filter '*.pgn' -File
if ($pgnFiles.Count -eq 0) {
    Write-Error "No .pgn files found under $PgnDir"
    exit 1
}
Write-Host "Found $($pgnFiles.Count) PGN file(s) under $PgnDir"

$allGames = New-Object System.Collections.Generic.List[object]
foreach ($file in $pgnFiles) {
    Write-Host "Replaying: $($file.Name)"
    $games = Invoke-PgnReplay -PgnPath $file.FullName
    foreach ($g in $games) { $allGames.Add($g) }
}
Write-Host "Total games replayed: $($allGames.Count)"
Write-Host ""

$sf = Start-StockfishEngine -Path $StockfishPath
$quietLines = New-Object System.Collections.Generic.List[string]
$blunderLines = New-Object System.Collections.Generic.List[string]
$openingGroups = @{}

$runQuiet = ($Mode -eq 'QuietCorpus' -or $Mode -eq 'All')
$runBlunder = ($Mode -eq 'BlunderCorpus' -or $Mode -eq 'All')
$runOpenings = ($Mode -eq 'OpeningStats' -or $Mode -eq 'All')

try {
    foreach ($game in $allGames) {
        # ─── Opening stats: always cheap, no Stockfish calls ─────────────────
        if ($runOpenings) {
            $key = $game.StartFen
            if (-not $openingGroups.ContainsKey($key)) {
                $openingGroups[$key] = [PSCustomObject]@{
                    Count      = 0
                    ResultSum  = 0.0
                    LengthSum  = 0
                }
            }
            $resultScore = 0.5
            if ($game.Result -eq '1-0') { $resultScore = 1.0 }
            elseif ($game.Result -eq '0-1') { $resultScore = 0.0 }
            $openingGroups[$key].Count++
            $openingGroups[$key].ResultSum += $resultScore
            $openingGroups[$key].LengthSum += $game.Plies.Count
        }

        if (-not $runQuiet -and -not $runBlunder) { continue }

        # Skip the opening: first 10 full moves = first 20 plies.
        $quietStreak = 0
        for ($i = 0; $i -lt $game.Plies.Count; $i++) {
            $ply = $game.Plies[$i]
            if ($ply.IsCapture -or $ply.IsCheck) {
                $quietStreak = 0
            } else {
                $quietStreak++
            }
            if ($ply.Ply -le 20) { continue }

            # ─── Quiet corpus: draw games only, quiet streak long enough ─────
            if ($runQuiet -and $game.Result -eq '1/2-1/2' -and $quietStreak -ge $MinQuietPlies) {
                $evalAfter = Get-StockfishEval -Proc $sf -Fen $ply.FenAfter -Depth $SfDepth
                if ($null -ne $evalAfter -and -not $evalAfter.IsMate -and [Math]::Abs($evalAfter.WhiteCp) -le $MaxAbsEvalCp) {
                    $evalBefore = Get-StockfishEval -Proc $sf -Fen $ply.FenBefore -Depth $SfDepth
                    # Both evals are already White-relative (Get-StockfishEval), so a plain
                    # difference is meaningful regardless of whose move fenBefore/fenAfter is.
                    $swing = 0
                    if ($null -ne $evalBefore -and -not $evalBefore.IsMate) {
                        $swing = [Math]::Abs($evalAfter.WhiteCp - $evalBefore.WhiteCp)
                    }
                    if ($swing -lt $BlunderThresholdCp) {
                        $fenFields = $ply.FenAfter -split ' '
                        $fen4 = ($fenFields[0..3] -join ' ')
                        $quietLines.Add("$fen4 c9 `"$($game.Result)`";")
                    }
                }
            }

            # ─── Blunder corpus: a large swing AGAINST the side that just moved ──
            if ($runBlunder) {
                $evalAfter = Get-StockfishEval -Proc $sf -Fen $ply.FenAfter -Depth $SfDepth
                $evalBefore = Get-StockfishEval -Proc $sf -Fen $ply.FenBefore -Depth $SfDepth
                if ($null -ne $evalAfter -and $null -ne $evalBefore -and -not $evalAfter.IsMate -and -not $evalBefore.IsMate) {
                    # fenBefore's side to move is who just played this ply; re-express both
                    # White-relative evals from THAT side's own perspective so the sign of
                    # the swing means "this side's position got worse (negative) or better
                    # (positive) from its own move" -- a blunder is a large negative swing.
                    $moverIsWhite = (($ply.FenBefore -split ' ')[1]) -eq 'w'
                    $moverBefore = if ($moverIsWhite) { $evalBefore.WhiteCp } else { -1 * $evalBefore.WhiteCp }
                    $moverAfter  = if ($moverIsWhite) { $evalAfter.WhiteCp }  else { -1 * $evalAfter.WhiteCp }
                    $swing = $moverAfter - $moverBefore
                    if ($swing -le (-1 * $BlunderThresholdCp)) {
                        $fenFields = $ply.FenBefore -split ' '
                        $fen4 = ($fenFields[0..3] -join ' ')
                        $blunderLines.Add("$fen4 c9 `"$($game.Result)`"; ce_before $moverBefore; ce_after $moverAfter; cp_drop $swing;")
                    }
                }
            }
        }
    }
} finally {
    $sf.StandardInput.WriteLine("quit")
    $sf.WaitForExit(2000) | Out-Null
}

# ─── Summary + (unless -DryRun) write outputs ────────────────────────────────

Write-Host ""
Write-Host "=== Summary ==="
if ($runQuiet) { Write-Host "Quiet corpus positions:   $($quietLines.Count)" }
if ($runBlunder) { Write-Host "Blunder corpus positions: $($blunderLines.Count)" }
if ($runOpenings) { Write-Host "Distinct openings seen:   $($openingGroups.Keys.Count)" }

if ($DryRun) {
    Write-Host ""
    Write-Host "-DryRun: no files written."
    exit 0
}

if ($runQuiet) {
    $quietPath = Join-Path $OutDir 'quiet-labeled-sprt.epd'
    Set-Content -Path $quietPath -Value $quietLines
    Write-Host "Wrote $quietPath ($($quietLines.Count) positions)"
}
if ($runBlunder) {
    $blunderPath = Join-Path $OutDir 'sprt_blunders.epd'
    Set-Content -Path $blunderPath -Value $blunderLines
    Write-Host "Wrote $blunderPath ($($blunderLines.Count) positions)"
}
if ($runOpenings) {
    $csvPath = Join-Path $OutDir 'sprt_opening_stats.csv'
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($key in $openingGroups.Keys) {
        $g = $openingGroups[$key]
        $rows.Add([PSCustomObject]@{
            opening_key = $key
            count       = $g.Count
            avg_result  = [math]::Round($g.ResultSum / $g.Count, 4)
            avg_length  = [math]::Round($g.LengthSum / $g.Count, 1)
        })
    }
    $rows | Export-Csv -Path $csvPath -NoTypeInformation
    Write-Host "Wrote $csvPath ($($rows.Count) distinct openings)"
}
