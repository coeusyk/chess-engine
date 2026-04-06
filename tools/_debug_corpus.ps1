#Requires -Version 7
<#
.SYNOPSIS
    Phase 12.2 (#130): Generate Stockfish-annotated Texel training corpus from self-play PGN.

.DESCRIPTION
    Parses cutechess-cli self-play PGN files, extracts quiet mid-game positions meeting all
    filter criteria, annotates each position with Stockfish at -Depth, and writes the result
    to texel_corpus.csv for use by the Texel tuner via --corpus.

    Quiet position criteria:
      - Last SAN move did not capture (no 'x' in SAN)
      - Resulting position has no check / given-check annotation (no '+' or '#' in SAN)
      - Move number > 10 (skip opening theory)
      - Total piece count > 6 (skip trivial endgames near tablebase territory)

    Sigmoid formula (VERIFIED against TunerEvaluator.java):
      sigma(cp) = 1 / (1 + 10^(-cp / K))
      where K = 400 / k_calibrated ≈ 570 (k_calibrated ≈ 0.701544 from last Texel run).
      This matches the tuner formula: 1/(1+10^(-k*eval/400)) exactly when K=400/k.

    Usage:
      .\tools\generate_texel_corpus.ps1 -StockfishPath C:\Tools\stockfish.exe -PgnDir tools\results

.PARAMETER PgnDir
    Directory containing *.pgn files (processes all .pgn files). Default: tools/results/

.PARAMETER StockfishPath
    Required. Full path to the Stockfish binary.

.PARAMETER Threads
    Stockfish annotation parallelism. One Stockfish process per thread. Default: 8.

.PARAMETER Depth
    Stockfish analysis depth per position. Default: 12.

.PARAMETER OutputCsv
    Output CSV file path. Default: data/texel_corpus.csv

.PARAMETER MaxPositions
    Maximum number of positions to include in the corpus. Default: 50000.

.PARAMETER K
    Sigmoid scaling constant K = 400 / k_calibrated.
    Default: 570 (matches TunerEvaluator.java with k_calibrated ≈ 0.701544).

.EXAMPLE
    .\tools\generate_texel_corpus.ps1 `
        -StockfishPath "C:\Tools\stockfish-windows-x86-64-avx2.exe" `
        -PgnDir "tools\results" `
        -Threads 4 `
        -MaxPositions 30000
#>
param(
    [string]$PgnDir         = (Join-Path $PSScriptRoot "results"),
    [string]$PgnFile        = "",
    [Parameter(Mandatory)][string]$StockfishPath,
    [int]   $Threads        = 8,
    [int]   $Depth          = 12,
    [string]$OutputCsv      = (Join-Path $PSScriptRoot ".." "data" "texel_corpus.csv"),
    [int]   $MaxPositions   = 50000,
    [double]$K              = 570.0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
$STANDARD_FEN_PREFIX = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
$MIN_PIECES          = 7    # > 6 = at least 7 pieces

# ---------------------------------------------------------------------------
# Stockfish validation
# ---------------------------------------------------------------------------
if (-not (Test-Path $StockfishPath)) {
    Write-Error "Stockfish binary not found: $StockfishPath"
    exit 1
}

Write-Host ""
Write-Host "Validating Stockfish..." -ForegroundColor Cyan
$_sfTmpOut = [System.IO.Path]::GetTempFileName()
$sf = Start-Process -FilePath $StockfishPath `
    -RedirectStandardOutput $_sfTmpOut -NoNewWindow -PassThru 2>$null
Start-Sleep -Milliseconds 500
if ($sf.HasExited) {
    Remove-Item $_sfTmpOut -ErrorAction SilentlyContinue
    Write-Error "Stockfish failed to start. Check the binary: $StockfishPath"
    exit 1
}
$sf.Kill(); $sf.WaitForExit()
Remove-Item $_sfTmpOut -ErrorAction SilentlyContinue

Write-Host "  Stockfish OK: $StockfishPath" -ForegroundColor Green

# ---------------------------------------------------------------------------
# Board tracker — minimal chess engine for FEN extraction from SAN moves
# ---------------------------------------------------------------------------

function New-ChessBoard([string]$fenStr) {
    # Parses a FEN position string and returns a board hashtable.
    # Board.sq: char[64], 0=a8..63=h1 (matches engine-core convention)
    # Board.side: 'w' or 'b'
    # Board.castling: string e.g. 'KQkq'
    # Board.ep: string ep square in SAN e.g. 'e3' or '-'
    $fenParts = $fenStr -split '\s+'
    $posPart  = $fenParts[0]
    $sq       = [char[]](' ' * 64)
    $i        = 0
    foreach ($c in $posPart.ToCharArray()) {
        if ($c -eq '/') { continue }
        if ($c -ge '1' -and $c -le '8') { $i += [int]$c - 48; continue }
        $sq[$i] = $c; $i++
    }
    return [PSCustomObject]@{
        sq       = $sq
        side     = if ($fenParts.Count -gt 1) { $fenParts[1] } else { 'w' }
        castling = if ($fenParts.Count -gt 2) { $fenParts[2] } else { 'KQkq' }
        ep       = if ($fenParts.Count -gt 3) { $fenParts[3] } else { '-' }
    }
}

function Board-ToFen($brd) {
    $rows = @()
    for ($r = 0; $r -lt 8; $r++) {
        $row = ''; $empty = 0
        for ($f = 0; $f -lt 8; $f++) {
            $pc = $brd.sq[$r * 8 + $f]
            if ($pc -eq ' ') { $empty++ }
            else { if ($empty -gt 0) { $row += $empty; $empty = 0 }; $row += $pc }
        }
        if ($empty -gt 0) { $row += $empty }
        $rows += $row
    }
    return ($rows -join '/') + " $($brd.side) $($brd.castling) $($brd.ep)"
}

function Board-PieceCount($brd) {
    $count = 0
    foreach ($pc in $brd.sq) { if ($pc -ne ' ') { $count++ } }
    return $count
}

function Sq([char]$file, [int]$rank) { return (8 - $rank) * 8 + ([int]$file - [int][char]'a') }
function Sq-File([int]$sq) { return [char]([int][char]'a' + ($sq % 8)) }
function Sq-Rank([int]$sq) { return 8 - [int]($sq / 8) }

function Can-SliderReach([char[]]$sq, [int]$from, [int]$to, [int[]]$dirs) {
    # Returns true if a slider on $from can reach $to along one of $dirs with no blockers.
    foreach ($d in $dirs) {
        $cur = $from + $d
        while ($cur -ge 0 -and $cur -lt 64) {
            # Prevent file wrapping — use previous step's file, not origin file
            $prevF = ($cur - $d) % 8; $curF = $cur % 8
            if ([Math]::Abs($d % 8) -eq 1 -and [Math]::Abs($curF - $prevF) -ne 1) { break }
            # Diagonal — check col distance is exactly 1 each step
            if ([Math]::Abs($d) -in @(7,9) -and [Math]::Abs($cur % 8 - ($cur - $d) % 8) -ne 1) { break }
            if ($cur -eq $to) { return $true }
            if ($sq[$cur] -ne ' ') { break }
            $cur += $d
        }
    }
    return $false
}

function Find-SourceSquare($brd, [char]$piece, [int]$toSq, [string]$disam) {
    # Finds the source square for a piece move considering disambiguation.
    # Returns the from-square index, or -1 if not found.
    $isWhite = $piece -ge 'A' -and $piece -le 'Z'
    $toFile  = $toSq % 8
    $toRank  = [int]($toSq / 8)  # 0=rank8..7=rank1 internally
    $candidates = @()

    for ($s = 0; $s -lt 64; $s++) {
        if ($brd.sq[$s] -ne $piece) { continue }
        $sFile = $s % 8; $sRank = [int]($s / 8)

        $canReach = switch ($piece) {
            { $_ -in 'N','n' } {
                $df = [Math]::Abs($sFile - $toFile); $dr = [Math]::Abs($sRank - $toRank)
                ($df -eq 1 -and $dr -eq 2) -or ($df -eq 2 -and $dr -eq 1)
            }
            { $_ -in 'B','b' } { Can-SliderReach $brd.sq $s $toSq @(-9,-7,7,9) }
            { $_ -in 'R','r' } { Can-SliderReach $brd.sq $s $toSq @(-8,-1,1,8) }
            { $_ -in 'Q','q' } { Can-SliderReach $brd.sq $s $toSq @(-9,-8,-7,-1,1,7,8,9) }
            { $_ -in 'K','k' } {
                $df = [Math]::Abs($sFile - $toFile); $dr = [Math]::Abs($sRank - $toRank)
                $df -le 1 -and $dr -le 1 -and ($df + $dr) -gt 0
            }
            default { $false }
        }
        if ($canReach) { $candidates += $s }
    }

    if ($candidates.Count -eq 0) { return -1 }
    if ($candidates.Count -eq 1) { return $candidates[0] }

    # Disambiguation
    if ($disam -match '^[a-h]$') {
        $f = [int][char]$disam[0] - [int][char]'a'
        foreach ($c in $candidates) { if ($c % 8 -eq $f) { return $c } }
    }
    if ($disam -match '^[1-8]$') {
        $r = 8 - [int]$disam[0] + 48  # e.g. '3' → rank 3 → internal row 5
        foreach ($c in $candidates) { if ([int]($c / 8) -eq $r) { return $c } }
    }
    if ($disam -match '^[a-h][1-8]$') {
        $f = [int][char]$disam[0] - [int][char]'a'
        $r = 8 - [int]$disam[1] + 48
        foreach ($c in $candidates) { if (($c % 8 -eq $f) -and ([int]($c / 8) -eq $r)) { return $c } }
    }
    return $candidates[0]  # Best guess if disambiguation unclear
}

function Apply-San($brd, [string]$san) {
    # Applies a SAN move to the board (modifies $brd in-place).
    # Returns $true on success, $false if the move could not be parsed.
    $san = $san -replace '[+#!?]',''
    if ($san -eq '') { return $true }

    $side = $brd.side

    # Castling
    if ($san -eq 'O-O') {
        if ($side -eq 'w') { $brd.sq[60]=' '; $brd.sq[62]='K'; $brd.sq[63]=' '; $brd.sq[61]='R' }
        else               { $brd.sq[4]=' ';  $brd.sq[6]='k';  $brd.sq[7]=' ';  $brd.sq[5]='r' }
        # Update castling rights
        if ($side -eq 'w') { $brd.castling = $brd.castling -replace '[KQ]','' }
        else               { $brd.castling = $brd.castling -replace '[kq]','' }
        if ($brd.castling -eq '') { $brd.castling = '-' }
        $brd.side = if ($side -eq 'w') { 'b' } else { 'w' }
        $brd.ep   = '-'
        return $true
    }
    if ($san -eq 'O-O-O') {
        if ($side -eq 'w') { $brd.sq[60]=' '; $brd.sq[58]='K'; $brd.sq[56]=' '; $brd.sq[59]='R' }
        else               { $brd.sq[4]=' ';  $brd.sq[2]='k';  $brd.sq[0]=' ';  $brd.sq[3]='r' }
        if ($side -eq 'w') { $brd.castling = $brd.castling -replace '[KQ]','' }
        else               { $brd.castling = $brd.castling -replace '[kq]','' }
        if ($brd.castling -eq '') { $brd.castling = '-' }
        $brd.side = if ($side -eq 'w') { 'b' } else { 'w' }
        $brd.ep   = '-'
        return $true
    }

    $brd.ep = '-'  # Clear ep before this move

    # Check for pawn promotion suffix: e8=Q
    $promoPiece = ''
    if ($san -match '=([QRBNqrbn])$') {
        $promoPiece = $Matches[1]
        if ($side -eq 'b') { $promoPiece = $promoPiece.ToLower() }
        else               { $promoPiece = $promoPiece.ToUpper() }
        $san = $san -replace '=[QRBNqrbn]$',''
    }

    # Pawn move (SAN starts with file letter a-h)
    if ($san[0] -ge 'a' -and $san[0] -le 'h') {
        $destStr = $san.Substring($san.Length - 2)
        $toFile  = [int][char]$destStr[0] - [int][char]'a'
        $toRank  = [int][char]$destStr[1] - 48   # 1..8
        $toSq    = (8 - $toRank) * 8 + $toFile
        $myPawn  = if ($side -eq 'w') { 'P' } else { 'p' }
        $dir     = if ($side -eq 'w') { 1 } else { -1 }  # Rank direction for returning
        $fromSq  = -1

        if ($san.Length -eq 2) {
            # Simple push
            $r1 = $toRank + $dir * (-1)
            if ($r1 -ge 1 -and $r1 -le 8) {
                $s1 = (8 - $r1) * 8 + $toFile
                if ($brd.sq[$s1] -eq $myPawn) { $fromSq = $s1 }
                else {
                    $r2 = $toRank + $dir * (-2)
                    if ($r2 -ge 1 -and $r2 -le 8) {
                        $s2 = (8 - $r2) * 8 + $toFile
                        if ($brd.sq[$s2] -eq $myPawn) { $fromSq = $s2 }
                    }
                }
            }
        } else {
            # Pawn capture: exd5 — from-file is san[0]
            $fromFile = [int][char]$san[0] - [int][char]'a'
            $fromRank = $toRank + $dir * (-1)
            $fromSq   = (8 - $fromRank) * 8 + $fromFile

            # En passant: target square is empty — remove the captured pawn
            if ($brd.sq[$toSq] -eq ' ') {
                $epCapSq = (8 - $toRank + $dir * (-1) + $dir) * 8 + $toFile
                # simpler: ep captured pawn is on same rank as fromSq, same file as toSq
                $epCapSq = (8 - $fromRank) * 8 + $toFile
                $brd.sq[$epCapSq] = ' '
            }
        }

        if ($fromSq -lt 0 -or $fromSq -ge 64) { $brd.side = if ($side -eq 'w') {'b'} else {'w'}; return $false }

        # Set ep square for double pawn push
        if ([Math]::Abs($toSq - $fromSq) -eq 16) {
            $epRank  = if ($side -eq 'w') { $toRank - 1 } else { $toRank + 1 }
            $epChar  = [char]([int][char]'a' + $toFile)
            $brd.ep  = "$epChar$epRank"
        }

        $brd.sq[$fromSq] = ' '
        $brd.sq[$toSq]   = if ($promoPiece -ne '') { $promoPiece } else { $myPawn }

        # Update castling rights on rook captures (if taken rook square)
        if ($toSq -eq 0)  { $brd.castling = $brd.castling -replace 'q','' }
        if ($toSq -eq 7)  { $brd.castling = $brd.castling -replace 'k','' }
        if ($toSq -eq 56) { $brd.castling = $brd.castling -replace 'Q','' }
        if ($toSq -eq 63) { $brd.castling = $brd.castling -replace 'K','' }
        if ($brd.castling -eq '') { $brd.castling = '-' }

        $brd.side = if ($side -eq 'w') { 'b' } else { 'w' }
        return $true
    }

    # Piece move (N/B/R/Q/K)
    $pieceChar = if ($side -eq 'w') { [char]$san[0] } else { [char]($san[0].ToString().ToLower()) }
    $rest      = ($san.Substring(1) -replace 'x','')  # Remove piece letter + captures
    $dest      = $rest.Substring($rest.Length - 2)    # Last 2 chars = destination
    $disam     = if ($rest.Length -gt 2) { $rest.Substring(0, $rest.Length - 2) } else { '' }
    $toFile    = [int][char]$dest[0] - [int][char]'a'
    $toRank    = [int][char]$dest[1] - 48
    $toSq      = (8 - $toRank) * 8 + $toFile

    $fromSq = Find-SourceSquare $brd $pieceChar $toSq $disam
    if ($fromSq -lt 0) { $brd.side = if ($side -eq 'w') {'b'} else {'w'}; return $false }

    # Update castling rights
    if ($fromSq -eq 4  -and $pieceChar -eq 'k') { $brd.castling = $brd.castling -replace '[kq]','' }
    if ($fromSq -eq 60 -and $pieceChar -eq 'K') { $brd.castling = $brd.castling -replace '[KQ]','' }
    if ($fromSq -eq 0)  { $brd.castling = $brd.castling -replace 'q','' }
    if ($fromSq -eq 7)  { $brd.castling = $brd.castling -replace 'k','' }
    if ($fromSq -eq 56) { $brd.castling = $brd.castling -replace 'Q','' }
    if ($fromSq -eq 63) { $brd.castling = $brd.castling -replace 'K','' }
    if ($toSq -eq 0)    { $brd.castling = $brd.castling -replace 'q','' }
    if ($toSq -eq 7)    { $brd.castling = $brd.castling -replace 'k','' }
    if ($toSq -eq 56)   { $brd.castling = $brd.castling -replace 'Q','' }
    if ($toSq -eq 63)   { $brd.castling = $brd.castling -replace 'K','' }
    if ($brd.castling -eq '') { $brd.castling = '-' }

    $brd.sq[$fromSq] = ' '
    $brd.sq[$toSq]   = $pieceChar
    $brd.side        = if ($side -eq 'w') { 'b' } else { 'w' }
    return $true
}

function Normalize-Fen4([string]$fen4) {
    # Normalise by zeroing ep field (avoids false duplicates across games)
    $p = $fen4 -split '\s+'
    if ($p.Count -lt 3) { return $fen4 }
    return "$($p[0]) $($p[1]) $($p[2]) -"
}

# ---------------------------------------------------------------------------
# WDL sigmoid
# ---------------------------------------------------------------------------
function To-Wdl([int]$cp, [double]$k) {
    return 1.0 / (1.0 + [Math]::Pow(10.0, -[double]$cp / $k))
}

# ---------------------------------------------------------------------------
# PGN parser — extract (FEN, game_result) pairs for quiet positions
# ---------------------------------------------------------------------------
function Extract-QuietPositions([string]$pgnPath) {
    $lines   = Get-Content $pgnPath
    # Split into games on [Event tags
    $games   = [System.Collections.Generic.List[object]]::new()
    $current = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $lines) {
        if ($line -match '^\[Event ') {
            if ($current.Count -gt 0) { $games.Add($current.ToArray()); $current.Clear() }
        }
        $current.Add($line)
    }
    if ($current.Count -gt 0) { $games.Add($current.ToArray()) }

    $positions = [System.Collections.Generic.List[PSCustomObject]]::new()

    foreach ($block in $games) {
        # Extract headers
        $result  = $null
        $startFen = $null
        foreach ($line in $block) {
            if ($line -match '^\[Result\s+"([^"]+)"\]') { $result   = $Matches[1] }
            if ($line -match '^\[FEN\s+"([^"]+)"\]')    { $startFen = $Matches[1] }
        }
        if (-not $result) { continue }
        $gameResult = switch ($result) { '1-0' { 1.0 } '0-1' { 0.0 } '1/2-1/2' { 0.5 } default { $null } }
        if ($null -eq $gameResult) { continue }

        # Parse initial FEN (use standard if absent)
        $fenStr  = if ($startFen) { $startFen } else { "$STANDARD_FEN_PREFIX w KQkq - 0 1" }
        $board   = New-ChessBoard $fenStr

        # Parse movetext — extract SAN tokens and move numbers
        $movetext = ($block | Where-Object { $_ -notmatch '^\[' }) -join ' '
        # Remove PGN evaluation comments {...} — collect SAN tokens and move numbers
        $SANs    = [System.Collections.Generic.List[string]]::new()
        $MoveNos = [System.Collections.Generic.List[int]]::new()
        $curMv   = 0
        # Use regex to find move number markers and SAN tokens, skip {...} comments
        $cleaned = [regex]::Replace($movetext, '\{[^}]*\}', ' ')
        $tokens  = $cleaned -split '\s+' | Where-Object { $_ -ne '' }
        foreach ($tok in $tokens) {
            if ($tok -match '^(\d+)\.*$') { $curMv = [int]$Matches[1]; continue }
            if ($tok -in @('1-0','0-1','1/2-1/2','*')) { continue }
            if ($tok -match '^[NBRQKP]?[a-h]?[1-8]?x?[a-h][1-8](=[QRBN])?[+#]?$' -or
                $tok -match '^O-O(-O)?$' -or $tok -match '^[a-h][1-8](=[QRBN])?[+#]?$') {
                $SANs.Add($tok)
                $MoveNos.Add($curMv)
            }
        }

        # Replay game, collect quiet positions
        for ($i = 0; $i -lt $SANs.Count; $i++) {
            $san = $SANs[$i]
            $mn  = $MoveNos[$i]

            # Quiet filter: no capture, no direct check, move > 10
            $isQuiet = ($san -notmatch 'x') -and ($san -notmatch '[+#]') -and ($mn -gt 10)

            # Apply move to advance board state
            $ok = Apply-San $board $san
            if (-not $ok) { Write-Warning "FAIL: $san at move $mn"; break }  # Board state becomes unreliable

            if ($isQuiet) {
                $pieceCount = Board-PieceCount $board
                if ($pieceCount -gt $MIN_PIECES) {
                    $fen4 = Board-ToFen $board
                    $positions.Add([PSCustomObject]@{
                        Fen4       = $fen4
                        GameResult = $gameResult
                    })
                }
            }
        }
    }
    return ,$positions  # Return as single object to preserve type
}

# ---------------------------------------------------------------------------
# Stockfish annotator — eval positions in batch using one SF process
# ---------------------------------------------------------------------------
function Invoke-StockfishBatch([PSCustomObject[]]$batch, [string]$sfPath, [int]$depth, [double]$kVal) {
    $proc = Start-Process -FilePath $sfPath `
        -RedirectStandardInput  "$env:TEMP\sf_in_$PID.txt" `
        -RedirectStandardOutput "$env:TEMP\sf_out_$PID.txt" `
        -PassThru -WindowStyle Hidden -NoNewWindow

    # Build command stream
    $cmds = [System.Text.StringBuilder]::new()
    [void]$cmds.AppendLine("uci")
    [void]$cmds.AppendLine("isready")
    foreach ($pos in $batch) {
        [void]$cmds.AppendLine("position fen $($pos.Fen4) 0 1")
        [void]$cmds.AppendLine("go depth $depth")
    }
    [void]$cmds.AppendLine("quit")
    [System.IO.File]::WriteAllText("$env:TEMP\sf_in_$PID.txt", $cmds.ToString())

    # Open pipe
    $sfIn  = $proc.StandardInput
    $sfOut = $proc.StandardOutput
    if ($null -eq $sfIn) {
        # Fallback: write file and start redirected
        Set-Content "$env:TEMP\sf_in_$PID.txt" $cmds.ToString()
    }

    # Restart with actual pipe
    if ($null -ne $sfIn) { $sfIn.Close() }
    $proc.WaitForExit(30000) | Out-Null
    Remove-Item "$env:TEMP\sf_in_$PID.txt"  -ErrorAction SilentlyContinue
    Remove-Item "$env:TEMP\sf_out_$PID.txt" -ErrorAction SilentlyContinue
    throw "Unsupported Stockfish invocation path"
}

# Better approach: interactive stdin/stdout
function New-StockfishProcess([string]$sfPath) {
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName               = $sfPath
    $psi.UseShellExecute        = $false
    $psi.RedirectStandardInput  = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $false
    $psi.CreateNoWindow         = $true
    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    [void]$proc.Start()

    # Send uci + isready handshake
    $proc.StandardInput.WriteLine("uci")
    $proc.StandardInput.WriteLine("isready")
    $proc.StandardInput.Flush()

    # Drain until "readyok"
    $timeout  = [DateTime]::UtcNow.AddSeconds(10)
    while ([DateTime]::UtcNow -lt $timeout) {
        $line = $proc.StandardOutput.ReadLine()
        if ($null -eq $line) { break }
        if ($line -eq 'readyok') { break }
    }
    return $proc
}

function Stop-StockfishProcess($proc) {
    try { $proc.StandardInput.WriteLine("quit"); $proc.StandardInput.Flush() } catch {}
    $proc.WaitForExit(3000) | Out-Null
    if (-not $proc.HasExited) { try { $proc.Kill() } catch {} }
}

function Get-StockfishEval($proc, [string]$fen4, [int]$depth) {
    # Returns eval in centipawns (from perspective of side to move), or $null on failure.
    $proc.StandardInput.WriteLine("position fen $fen4 0 1")
    $proc.StandardInput.WriteLine("go depth $depth")
    $proc.StandardInput.Flush()

    $evalCp  = $null
    $timeout = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $timeout) {
        $line = $proc.StandardOutput.ReadLine()
        if ($null -eq $line) { break }
        # Parse: info ... score cp <N> ...  OR  info ... score mate <N>
        if ($line -match 'info.*\bscore cp (-?\d+)') {
            $evalCp = [int]$Matches[1]
        }
        if ($line -match 'info.*\bscore mate (-?\d+)') {
            $mateN  = [int]$Matches[1]
            $evalCp = if ($mateN -gt 0) { 30000 } else { -30000 }
        }
        if ($line -match '^bestmove') { break }
    }
    return $evalCp
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

# Resolve PGN files
if ($PgnFile -ne "") {
    $PgnFile = Resolve-Path $PgnFile -ErrorAction Stop | Select-Object -ExpandProperty Path
    if (-not (Test-Path $PgnFile -PathType Leaf)) {
        Write-Error "PGN file not found: $PgnFile"
        exit 1
    }
    $pgnFiles = @(Get-Item $PgnFile)
} else {
    if (-not (Test-Path $PgnDir)) {
        Write-Error "PGN directory not found: $PgnDir"
        exit 1
    }
    $pgnFiles = @(Get-ChildItem -Path $PgnDir -Filter "*.pgn" -File)
    if ($pgnFiles.Count -eq 0) {
        Write-Warning "No .pgn files found in: $PgnDir"
        exit 0
    }
}

# Ensure output dir exists
$outDir = Split-Path $OutputCsv -Parent
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

Write-Host ""
Write-Host "Generate Texel Corpus" -ForegroundColor Cyan
Write-Host "=====================" -ForegroundColor Cyan
if ($PgnFile -ne "") {
    Write-Host "PGN file     : $PgnFile"
} else {
    Write-Host "PGN dir      : $PgnDir ($($pgnFiles.Count) files)"
}
Write-Host "Stockfish    : $StockfishPath"
Write-Host "Threads      : $Threads"
Write-Host "Depth        : $Depth"
Write-Host "Max positions: $MaxPositions"
Write-Host "K (sigmoid)  : $K  [K = 400/k_calibrated ≈ 400/0.701544]"
Write-Host "Output CSV   : $OutputCsv"
Write-Host ""

# ── Step 1: Extract quiet positions from all PGN files ──
Write-Host "Step 1: Extracting quiet positions from PGN files..." -ForegroundColor Cyan
$allPositions = [System.Collections.Generic.List[PSCustomObject]]::new()
$knownFens    = [System.Collections.Generic.HashSet[string]]::new()

foreach ($f in $pgnFiles) {
    Write-Host "  $($f.Name)" -ForegroundColor DarkCyan
    try {
        $pos = Extract-QuietPositions $f.FullName
        foreach ($p in $pos) {
            $norm = Normalize-Fen4 $p.Fen4
            if ($knownFens.Add($norm)) {
                $allPositions.Add($p)
                if ($allPositions.Count -ge $MaxPositions) { break }
            }
        }
        Write-Host "    Positions so far: $($allPositions.Count)"
    } catch {
        Write-Warning "    Error: $_"
    }
    if ($allPositions.Count -ge $MaxPositions) { break }
}

$totalPos = $allPositions.Count
Write-Host ""
Write-Host "Extracted $totalPos unique quiet positions total." -ForegroundColor Green
if ($totalPos -eq 0) {
    Write-Warning "No quiet positions extracted — check PGN files."
    exit 1
}

# ── Step 2: Annotate with Stockfish in parallel batches ──
Write-Host ""
Write-Host "Step 2: Annotating with Stockfish (threads=$Threads, depth=$Depth)..." -ForegroundColor Cyan

$batchSize   = [Math]::Max(1, [int][Math]::Ceiling($totalPos / $Threads))
$batches     = @()
for ($i = 0; $i -lt $totalPos; $i += $batchSize) {
    $end = [Math]::Min($i + $batchSize - 1, $totalPos - 1)
    $batches += ,($allPositions[$i..$end])
}

$sfPath = $StockfishPath
$sfDepth = $Depth
$sigK = $K

$annotated = $batches | ForEach-Object -Parallel {
    $batch  = $_
    $sfPath = $using:sfPath
    $sfDepth = $using:sfDepth
    $sigK   = $using:sigK
    $results = [System.Collections.Generic.List[PSCustomObject]]::new()

    # Start one Stockfish process for this thread
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName               = $sfPath
    $psi.UseShellExecute        = $false
    $psi.RedirectStandardInput  = $true
    $psi.RedirectStandardOutput = $true
    $psi.CreateNoWindow         = $true
    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    [void]$proc.Start()

    # Handshake
    $proc.StandardInput.WriteLine("uci")
    $proc.StandardInput.WriteLine("isready")
    $proc.StandardInput.Flush()
    $timo = [DateTime]::UtcNow.AddSeconds(10)
    while ([DateTime]::UtcNow -lt $timo) {
        $l = $proc.StandardOutput.ReadLine()
        if ($null -eq $l -or $l -eq 'readyok') { break }
    }

    foreach ($pos in $batch) {
        try {
            $proc.StandardInput.WriteLine("position fen $($pos.Fen4) 0 1")
            $proc.StandardInput.WriteLine("go depth $sfDepth")
            $proc.StandardInput.Flush()

            $evalCp  = $null
            $timo2   = [DateTime]::UtcNow.AddSeconds(60)
            while ([DateTime]::UtcNow -lt $timo2) {
                $line = $proc.StandardOutput.ReadLine()
                if ($null -eq $line) { break }
                if ($line -match 'info.*\bscore cp (-?\d+)') { $evalCp = [int]$Matches[1] }
                if ($line -match 'info.*\bscore mate (-?\d+)') {
                    $evalCp = if ([int]$Matches[1] -gt 0) { 30000 } else { -30000 }
                }
                if ($line -match '^bestmove') { break }
            }

            if ($null -ne $evalCp) {
                $wdl = 1.0 / (1.0 + [Math]::Pow(10.0, -[double]$evalCp / $sigK))
                $results.Add([PSCustomObject]@{
                    Fen        = $pos.Fen4
                    Wdl        = [Math]::Round($wdl, 6)
                    GameResult = $pos.GameResult
                })
            }
        } catch {
            # Skip on error — continue with next position
        }
    }

    # Quit Stockfish
    try { $proc.StandardInput.WriteLine("quit"); $proc.StandardInput.Flush() } catch {}
    $proc.WaitForExit(3000) | Out-Null
    if (-not $proc.HasExited) { try { $proc.Kill() } catch {} }

    return ,$results
} -ThrottleLimit $Threads

# Flatten results
$allAnnotated = [System.Collections.Generic.List[PSCustomObject]]::new()
foreach ($batch in $annotated) { foreach ($r in $batch) { $allAnnotated.Add($r) } }

Write-Host "Annotated $($allAnnotated.Count) positions."

# ── Step 3: Write CSV ──
Write-Host ""
Write-Host "Step 3: Writing CSV to $OutputCsv..." -ForegroundColor Cyan

$csvLines = [System.Text.StringBuilder]::new()
[void]$csvLines.AppendLine("fen,wdl_stockfish,game_result")
foreach ($row in $allAnnotated) {
    $fenEsc = $row.Fen -replace '"','""'
    [void]$csvLines.AppendLine("`"$fenEsc`",$($row.Wdl),$($row.GameResult)")
}

[System.IO.File]::WriteAllText($OutputCsv, $csvLines.ToString(), [System.Text.Encoding]::UTF8)

$rowCount = $allAnnotated.Count
Write-Host ""
Write-Host "Corpus written: $rowCount positions to $OutputCsv" -ForegroundColor Green
Write-Host "CSV columns  : fen, wdl_stockfish, game_result"
Write-Host "Next step    : run engine-tuner with --corpus $OutputCsv"

# Print a sample row for quick sanity check
if ($allAnnotated.Count -gt 0) {
    $sample = $allAnnotated[0]
    Write-Host ""
    Write-Host "Sample row:" -ForegroundColor DarkCyan
    Write-Host "  FEN        : $($sample.Fen)"
    Write-Host "  WDL        : $($sample.Wdl)"
    Write-Host "  GameResult : $($sample.GameResult)"
}

