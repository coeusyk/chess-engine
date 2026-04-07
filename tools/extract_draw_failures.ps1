#Requires -Version 5.1
<#
.SYNOPSIS
    Phase 12.1 (#129): Extract draw-from-winning-position failures from SPRT/self-play PGN output.

.DESCRIPTION
    Scans cutechess-cli PGN output for games ending in 1/2-1/2 where the engine had a
    significant advantage (|eval| > ThresholdCp) in the final 20 plies. The starting FEN
    of each qualifying game (from the [FEN] header, or the standard position if absent)
    is appended to draw_failures.epd as a regression seed.

    Run after every SPRT batch:
        .\tools\extract_draw_failures.ps1 -PgnDir tools\results

.PARAMETER PgnFile
    Path to a single .pgn file to process.

.PARAMETER PgnDir
    Directory containing *.pgn files. All matching files are processed.

.PARAMETER EpdOut
    Output EPD file. Default: engine-core/src/test/resources/regression/draw_failures.epd

.PARAMETER ThresholdCp
    Minimum |eval| in centipawns to flag a draw as a failure. Default: 200.

.EXAMPLE
    # Process all PGNs in results/ — append new failures to the default EPD
    .\tools\extract_draw_failures.ps1 -PgnDir tools\results

    # Process a single file with a custom threshold
    .\tools\extract_draw_failures.ps1 -PgnFile tools\results\sprt_20260406.pgn -ThresholdCp 300

.NOTES
    FEN Extraction:
      - Games with a [FEN "..."] header (e.g., SPRT with -openings book): uses that FEN
        as the starting position for the regression entry.
      - Games without [FEN] (standard start, e.g., selfplay batches): uses the standard
        starting FEN rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1.
      - Per-move FEN reconstruction from move notation is out of scope (no chess library).

    Deduplication:
      - Normalises FEN by zeroing the ep-square field (avoids false duplicates for the
        same position with different en-passant metadata across games).
      - Compares normalised FEN against all existing entries in EpdOut before appending.

    No dependencies outside PowerShell stdlib.
#>
param(
    [string]$PgnFile      = "",
    [string]$PgnDir       = "",
    [string]$EpdOut       = (Join-Path $PSScriptRoot ".." "engine-core" "src" "test" "resources" "regression" "draw_failures.epd"),
    [int]   $ThresholdCp  = 200
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
$STANDARD_FEN   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
$EVAL_REGEX     = [regex]'\{([+-]?\d+(?:\.\d+)?)/\d+'
$DRAW_SKIP_PAT  = 'Draw by adjudication'
$DRAW_KEEP_PATS = @('Draw by 3-fold repetition','Draw by repetition','Draw by 50-move rule','Draw by stalemate','Draw by insufficient mating material')

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

function Normalize-Fen([string]$fen) {
    # Extract first 3 fields (pos, side, castling) then zero ep-square field.
    # Ignores halfmove / fullmove counters so two positions with different clock
    # values but identical board state share the same normalised key.
    $parts = $fen -split '\s+'
    if ($parts.Count -lt 3) { return $fen }
    return "$($parts[0]) $($parts[1]) $($parts[2]) -"
}

function Load-ExistingFens([string]$path) {
    $set = [System.Collections.Generic.HashSet[string]]::new()
    if (-not (Test-Path $path)) { return $set }
    foreach ($line in (Get-Content $path)) {
        $line = $line.Trim()
        if ($line -eq "" -or $line.StartsWith("#")) { continue }
        # EPD line: first 4 whitespace-delimited tokens are the FEN fields
        $parts = $line -split '\s+'
        if ($parts.Count -ge 4) {
            $fen = "$($parts[0]) $($parts[1]) $($parts[2]) $($parts[3])"
            [void]$set.Add((Normalize-Fen $fen))
        }
    }
    return $set
}

function Parse-Games([string[]]$lines) {
    # Split the raw lines into per-game blocks.
    # A new game starts whenever a [Event tag is encountered.
    $games    = [System.Collections.Generic.List[string[]]]::new()
    $current  = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $lines) {
        if ($line -match '^\[Event ') {
            if ($current.Count -gt 0) {
                $games.Add($current.ToArray())
                $current = [System.Collections.Generic.List[string]]::new()
            }
        }
        $current.Add($line)
    }
    if ($current.Count -gt 0) { $games.Add($current.ToArray()) }
    return $games
}

function Analyze-Game([string[]]$block) {
    # Extracts result, starting FEN, draw type, eval sequence, and last SAN move from one game block.
    $result    = $null
    $fen       = $null
    $drawType  = $null
    $lastSan   = $null
    $evals     = [System.Collections.Generic.List[double]]::new()
    $moveNums  = [System.Collections.Generic.List[int]]::new()
    $currentMoveNum = 0
    # SAN move token regex: handles normal moves, captures, promotions, check/mate, and castling
    $SAN_REGEX = [regex]'^([NBRQK]?[a-h]?[1-8]?x?[a-h][1-8](?:=[NBRQ])?[+#]?|O-O(?:-O)?[+#]?)\s*'

    foreach ($line in $block) {
        # Headers
        if ($line -match '^\[Result\s+"([^"]+)"\]')      { $result   = $Matches[1]; continue }
        if ($line -match '^\[FEN\s+"([^"]+)"\]')          { $fen      = $Matches[1]; continue }

        # Movetext — extract eval comments {score/depth ...} and move numbers
        $pos = 0
        while ($pos -lt $line.Length) {
            # Detect move number (e.g., "42." or "42...")
            if ($line.Substring($pos) -match '^(\d+)\.+\s*') {
                $currentMoveNum = [int]$Matches[1]
                $pos += $Matches[0].Length
                continue
            }
            # Detect SAN move token (appears between move number and eval comment)
            $m = $SAN_REGEX.Match($line.Substring($pos))
            if ($m.Success -and $m.Index -eq 0 -and $line[$pos] -ne '{') {
                $lastSan = $m.Groups[1].Value
                $pos    += $m.Length
                continue
            }
            # Eval comment {+1.23/9 0.45s, ...}
            $remaining = $line.Substring($pos)
            $m = $EVAL_REGEX.Match($remaining)
            if ($m.Success -and $m.Index -eq 0) {
                $evalPawns = [double]$m.Groups[1].Value
                $evalCp    = [int]($evalPawns * 100)
                $evals.Add($evalCp)
                $moveNums.Add($currentMoveNum)
                # Check for draw termination inside this comment
                $commentEnd = $remaining.IndexOf('}')
                if ($commentEnd -ge 0) {
                    $comment = $remaining.Substring(0, $commentEnd + 1)
                    foreach ($pat in $DRAW_KEEP_PATS) {
                        if ($comment -match [regex]::Escape($pat)) { $drawType = $pat; break }
                    }
                    if ($comment -match [regex]::Escape($DRAW_SKIP_PAT)) { $drawType = $DRAW_SKIP_PAT }
                    $pos += $commentEnd + 1
                } else {
                    $pos += $m.Length
                }
                continue
            }
            $pos++
        }
    }

    return [PSCustomObject]@{
        Result   = $result
        Fen      = if ($fen) { $fen } else { $STANDARD_FEN }
        DrawType = $drawType
        LastSan  = $lastSan
        Evals    = $evals.ToArray()
        MoveNums = $moveNums.ToArray()
    }
}

function Find-EarliestFailure([double[]]$evals, [int[]]$moveNums, [int]$threshold) {
    # Scan the LAST 20 evals for |eval| > threshold.
    # Returns the [evalCp, moveNum] of the EARLIEST (oldest) flagged eval,
    # or $null if no failure found.
    $count      = $evals.Count
    $windowStart = [Math]::Max(0, $count - 20)
    $result      = $null

    for ($i = $windowStart; $i -lt $count; $i++) {
        if ([Math]::Abs($evals[$i]) -gt $threshold) {
            # Return the first (earliest) flagged position
            return [PSCustomObject]@{ EvalCp = [int]$evals[$i]; MoveNum = $moveNums[$i] }
        }
    }
    return $null
}

function Process-PgnFile([string]$pgnPath, [System.Collections.Generic.HashSet[string]]$knownFens,
                          [System.IO.StreamWriter]$writer, [int]$threshold) {
    $filename   = Split-Path -Leaf $pgnPath
    $lines      = Get-Content $pgnPath -ErrorAction Stop
    $games      = Parse-Games $lines

    $totalGames  = 0
    $drawGames   = 0
    $failures    = 0
    $duplicates  = 0

    foreach ($block in $games) {
        $totalGames++
        $info = Analyze-Game $block

        # Only care about drawn games
        if ($info.Result -ne "1/2-1/2") { continue }
        $drawGames++

        # Skip correct draws (adjudicated by cutechess score threshold)
        if ($info.DrawType -eq $DRAW_SKIP_PAT) { continue }

        # Find earliest flagged eval in the last 20 plies
        $fail = Find-EarliestFailure $info.Evals $info.MoveNums $threshold
        if (-not $fail) { continue }

        # Deduplicate using normalised FEN
        $normFen = Normalize-Fen $info.Fen
        if ($knownFens.Contains($normFen)) {
            $duplicates++
            continue
        }

        # Extract 4-field FEN (strip halfmove/fullmove counters for EPD)
        $fenParts = $info.Fen -split '\s+'
        $epd4     = if ($fenParts.Count -ge 4) {
            "$($fenParts[0]) $($fenParts[1]) $($fenParts[2]) $($fenParts[3])"
        } else { $info.Fen }

        $label    = "draw_failure $filename move $($fail.MoveNum) eval $($fail.EvalCp)cp"
        $bmPart   = if ($info.LastSan) { "bm $($info.LastSan); " } else { "" }
        $line     = "$epd4 $($bmPart)c0 `"$label`";"
        $writer.WriteLine($line)
        [void]$knownFens.Add($normFen)
        $failures++
    }

    return [PSCustomObject]@{
        TotalGames = $totalGames
        DrawGames  = $drawGames
        Failures   = $failures
        Duplicates = $duplicates
    }
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

# Validate inputs
if (-not $PgnFile -and -not $PgnDir) {
    Write-Error "Provide -PgnFile <path> or -PgnDir <directory>."
    exit 1
}

# Resolve list of PGN files
$pgnFiles = [System.Collections.Generic.List[string]]::new()
if ($PgnFile) {
    if (-not (Test-Path $PgnFile)) { Write-Error "PGN file not found: $PgnFile"; exit 1 }
    $pgnFiles.Add((Resolve-Path $PgnFile).Path)
}
if ($PgnDir) {
    if (-not (Test-Path $PgnDir)) { Write-Error "PGN directory not found: $PgnDir"; exit 1 }
    Get-ChildItem -Path $PgnDir -Filter "*.pgn" -File | ForEach-Object { $pgnFiles.Add($_.FullName) }
}
if ($pgnFiles.Count -eq 0) {
    Write-Warning "No .pgn files found in the specified location."
    exit 0
}

# Ensure output directory exists
$epdDir = Split-Path $EpdOut -Parent
if (-not (Test-Path $epdDir)) {
    New-Item -ItemType Directory -Path $epdDir -Force | Out-Null
}

# Load existing FENs for deduplication
$knownFens = Load-ExistingFens $EpdOut

# Open EPD file for appending
$writer = [System.IO.StreamWriter]::new($EpdOut, $true, [System.Text.Encoding]::UTF8)

# Process files
$grandTotal  = 0
$grandDraws  = 0
$grandFails  = 0
$grandDups   = 0

Write-Host ""
Write-Host "Extract Draw Failures" -ForegroundColor Cyan
Write-Host "=====================" -ForegroundColor Cyan
Write-Host "PGN files   : $($pgnFiles.Count)"
Write-Host "EPD output  : $EpdOut"
Write-Host "Threshold   : $ThresholdCp cp"
Write-Host ""

foreach ($f in $pgnFiles) {
    Write-Host "Processing: $(Split-Path -Leaf $f)" -ForegroundColor DarkCyan
    try {
        $stats = Process-PgnFile $f $knownFens $writer $ThresholdCp
        Write-Host "  Games=$($stats.TotalGames) Draws=$($stats.DrawGames) Failures=$($stats.Failures) Duplicates=$($stats.Duplicates)"
        $grandTotal += $stats.TotalGames
        $grandDraws += $stats.DrawGames
        $grandFails += $stats.Failures
        $grandDups  += $stats.Duplicates
    } catch {
        Write-Warning "  Error processing file: $_"
    }
}

$writer.Flush()
$writer.Dispose()

Write-Host ""
Write-Host "Summary" -ForegroundColor Cyan
Write-Host "-------" -ForegroundColor Cyan
Write-Host "Total games parsed : $grandTotal"
Write-Host "Draw games found   : $grandDraws"
Write-Host "Draw failures      : $grandFails"
Write-Host "Duplicates skipped : $grandDups"
Write-Host "New FENs added     : $grandFails"
Write-Host ""
if ($grandFails -gt 0) {
    Write-Host "Draw failures appended to: $EpdOut" -ForegroundColor Green
} else {
    Write-Host "No new draw failures found." -ForegroundColor Yellow
}
