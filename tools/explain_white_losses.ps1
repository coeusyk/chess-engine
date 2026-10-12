<#
.SYNOPSIS
    Finds games where NEW played White and lost, ranks them by eval collapse that
    happens AFTER the opening (ply >= SkipOpenPly), then walks the board forward
    using a pure-PowerShell SAN→LAN converter to extract the exact pre-blunder
    FEN, and runs the engine's built-in 'eval' UCI command on it.

    No Python, no FenWalker, no Stockfish.  All logic is PowerShell + engine JAR.

.PARAMETER PgnFile
    Path to the SPRT PGN file (cutechess output with {score/depth time} annotations).

.PARAMETER TopN
    Number of worst losses to analyze. Default: 15

.PARAMETER SkipOpenPly
    Plies to ignore at game start when looking for eval collapses.
    Default: 30 (= 15 full moves each side). Collapses inside the opening are
    ignored even if they are the largest drop in the game.

.PARAMETER OutFile
    Output file for eval breakdowns. Default: tools/output/explain_eval_results.txt

.EXAMPLE
    Push-Location chess-engine
    $env:JAVA_HOME = "C:\Tools\Java\zulu-21"
    .\tools\explain_white_losses.ps1 -PgnFile "tools\results\sprt_phase14-king-safety-stc_20260423_112431.pgn"
    Pop-Location
#>
param(
    [Parameter(Mandatory)][string]$PgnFile,
    [int]   $TopN        = 15,
    [int]   $SkipOpenPly = 30,
    [string]$OutFile     = "tools\output\explain_eval_results.txt",
    [string]$SkippedRawFile = "tools\output\explain_eval_skipped_raw.txt",
    [int]$AnchorValidationN = 5,
    [int]$TemporalSampleN = 50
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$VexJar = "engine-uci\target\engine-uci-0.5.7-SNAPSHOT-shaded.jar"
$Java   = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }

if (-not (Test-Path $VexJar)) { throw "Engine JAR not found: $VexJar" }
if (-not (Test-Path $PgnFile)) { throw "PGN not found: $PgnFile" }

# Resolve all paths to absolute so .NET calls work regardless of Push-Location CWD
$PgnFile = (Resolve-Path $PgnFile).Path
$VexJar  = (Resolve-Path $VexJar).Path
$OutFile  = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutFile)
$SkippedRawFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($SkippedRawFile)

# ── Board state helpers — minimal SAN→LAN converter + FEN tracker ─────────────

# Parse a FEN string into a mutable board state hashtable.
# Squares hashtable: square-name ('e4') → piece-code ('wP','bK',…); absent = empty.
function New-BoardFromFen([string]$fen) {
    $parts      = $fen.Trim() -split '\s+'
    $sqs        = @{}
    $rankStrings = $parts[0] -split '/'
    for ($r = 0; $r -le 7; $r++) {         # r=0 → rank 8 in FEN (top row)
        $rankIdx = 7 - $r                  # 0-indexed from White's base: 0=rank1, 7=rank8
        $fileIdx = 0
        foreach ($ch in $rankStrings[$r].ToCharArray()) {
            if ($ch -ge '1' -and $ch -le '8') {
                $fileIdx += [int]::Parse($ch.ToString())
            } else {
                $color = if ([char]::IsUpper($ch)) { 'w' } else { 'b' }
                $sq    = [string]'abcdefgh'[$fileIdx] + ($rankIdx + 1)
                $sqs[$sq] = $color + [char]::ToUpper($ch).ToString()
                $fileIdx++
            }
        }
    }
    return @{
        Squares   = $sqs
        Turn      = if ($parts.Length -gt 1) { $parts[1] } else { 'w' }
        Castling  = if ($parts.Length -gt 2 -and $parts[2]) { $parts[2] } else { 'KQkq' }
        EnPassant = if ($parts.Length -gt 3) { $parts[3] } else { '-' }
        HalfMove  = if ($parts.Length -gt 4) { [int]$parts[4] } else { 0 }
        FullMove  = if ($parts.Length -gt 5) { [int]$parts[5] } else { 1 }
    }
}

# True when every intermediate square on the ray src→dst is empty.
# Caller guarantees src and dst are on the same rank, file, or diagonal.
function Test-SliderClear($sqs, [string]$src, [string]$dst) {
    $sf   = 'abcdefgh'.IndexOf($src[0].ToString())
    $sr   = [int]::Parse($src[1].ToString()) - 1
    $df   = 'abcdefgh'.IndexOf($dst[0].ToString())
    $dr   = [int]::Parse($dst[1].ToString()) - 1
    $stepF = if ($df -gt $sf) { 1 } elseif ($df -lt $sf) { -1 } else { 0 }
    $stepR = if ($dr -gt $sr) { 1 } elseif ($dr -lt $sr) { -1 } else { 0 }
    $cf = $sf + $stepF
    $cr = $sr + $stepR
    while ($cf -ne $df -or $cr -ne $dr) {
        if ($sqs.ContainsKey([string]'abcdefgh'[$cf] + ($cr + 1))) { return $false }
        $cf += $stepF
        $cr += $stepR
    }
    return $true
}

# Convert one SAN token to LAN (e.g. 'Nf3' → 'g1f3') given the current board state.
# Returns $null when the SAN cannot be resolved (parse error / illegal move).
function Convert-SanToLan($state, [string]$san) {
    $col   = $state.Turn
    $sqs   = $state.Squares
    $dir   = if ($col -eq 'w') { 1 } else { -1 }
    $homeR = if ($col -eq 'w') { 1 } else { 6 }   # 0-indexed pawn starting rank

    $san = $san -replace '[+#!?]', ''              # strip check / annotation noise

    if ($san -eq 'O-O-O') { if ($col -eq 'w') { return 'e1c1' } else { return 'e8c8' } }
    if ($san -eq 'O-O')   { if ($col -eq 'w') { return 'e1g1' } else { return 'e8g8' } }

    # Promotion  (e8=Q  or  e8Q)
    $promo = ''
    if ($san -cmatch '=([NBRQ])$') {
        $promo = $Matches[1].ToLower(); $san = $san -replace '=[NBRQ]$', ''
    } elseif ($san.Length -gt 2 -and $san[-1] -cmatch '[NBRQ]' -and $san[0] -cnotmatch '[NBRQK]') {
        $promo = $san[-1].ToString().ToLower(); $san = $san.Substring(0, $san.Length - 1)
    }

    $isCapture = $san.Contains('x')
    $san       = $san -replace 'x', ''

    $piece   = $col + 'P'
    $dstSq   = ''
    $disambF = ''
    $disambR = ''

    if ($san.Length -gt 0 -and $san[0] -cmatch '[NBRQK]') {
        $piece  = $col + $san[0]
        $rest   = $san.Substring(1)
        $dstSq  = if ($rest.Length -ge 2) { $rest.Substring($rest.Length - 2) } else { $rest }
        $disamb = if ($rest.Length -gt 2) { $rest.Substring(0, $rest.Length - 2) } else { '' }
        if     ($disamb -match '^([a-h])$') { $disambF = $disamb }
        elseif ($disamb -match '^([1-8])$') { $disambR = $disamb }
        elseif ($disamb -match '^([a-h])([1-8])$') {
            # Fully qualified source square — rare but valid
            $disambF = $Matches[1]; $disambR = $Matches[2]
        }
    } else {
        if ($isCapture -or ($san.Length -eq 3 -and $san[1] -match '[a-h]')) {
            $disambF = [string]$san[0]
            $dstSq   = $san.Substring($san.Length - 2)
        } else {
            $dstSq = $san
        }
    }

    if ($dstSq.Length -lt 2) { return $null }
    $destF = 'abcdefgh'.IndexOf($dstSq[0].ToString())
    $destR = [int]::Parse($dstSq[1].ToString()) - 1
    if ($destF -lt 0 -or $destR -lt 0 -or $destR -gt 7) { return $null }

    $pieceType = $piece[1].ToString()
    $srcSq     = $null

    foreach ($sq in @($sqs.Keys)) {
        if ($sqs[$sq] -ne $piece)                             { continue }
        if ($disambF -and $sq[0].ToString() -ne $disambF)    { continue }
        if ($disambR -and $sq[1].ToString() -ne $disambR)    { continue }

        $sf = 'abcdefgh'.IndexOf($sq[0].ToString())
        $sr = [int]::Parse($sq[1].ToString()) - 1
        $dF = $destF - $sf
        $dR = $destR - $sr

        $ok = $false
        switch ($pieceType) {
            'P' {
                if ($isCapture -or $disambF) {
                    $ok = ($dR -eq $dir -and [Math]::Abs($dF) -eq 1)
                    if (-not $ok -and $state.EnPassant -ne '-' -and $dstSq -eq $state.EnPassant) {
                        $ok = ($dR -eq $dir -and [Math]::Abs($dF) -eq 1)
                    }
                } else {
                    if ($dF -eq 0 -and $dR -eq $dir) {
                        $ok = -not $sqs.ContainsKey([string]'abcdefgh'[$sf] + ($sr + $dir + 1))
                    } elseif ($dF -eq 0 -and $dR -eq (2 * $dir) -and $sr -eq $homeR) {
                        $m1 = [string]'abcdefgh'[$sf] + ($sr + $dir + 1)
                        $m2 = [string]'abcdefgh'[$sf] + ($sr + 2 * $dir + 1)
                        $ok = -not $sqs.ContainsKey($m1) -and -not $sqs.ContainsKey($m2)
                    }
                }
            }
            'N' { $ok = ([Math]::Abs($dF) -eq 2 -and [Math]::Abs($dR) -eq 1) -or
                        ([Math]::Abs($dF) -eq 1 -and [Math]::Abs($dR) -eq 2) }
            'B' { $ok = ([Math]::Abs($dF) -eq [Math]::Abs($dR)) -and $dF -ne 0 -and
                        (Test-SliderClear $sqs $sq $dstSq) }
            'R' { $ok = (($dF -eq 0) -ne ($dR -eq 0)) -and
                        (Test-SliderClear $sqs $sq $dstSq) }
            'Q' { $isDiag = ([Math]::Abs($dF) -eq [Math]::Abs($dR)) -and $dF -ne 0
                  $isStraight = ($dF -eq 0) -ne ($dR -eq 0)
                  $ok = ($isDiag -or $isStraight) -and (Test-SliderClear $sqs $sq $dstSq) }
            'K' { $ok = [Math]::Abs($dF) -le 1 -and [Math]::Abs($dR) -le 1 -and
                        ($dF -ne 0 -or $dR -ne 0) }
        }

        if ($ok) {
            if ($null -ne $srcSq) { break }   # ambiguous — take first (PGN should not be ambiguous)
            $srcSq = $sq
        }
    }

    if ($null -eq $srcSq) { return $null }
    return $srcSq + $dstSq + $promo
}

# Apply a LAN move to the board state (mutates $state in-place; also returns it).
function Apply-LanToState($state, [string]$lan) {
    $src   = $lan.Substring(0, 2)
    $dst   = $lan.Substring(2, 2)
    $col   = $state.Turn
    $sqs   = $state.Squares
    $piece = $sqs[$src]
    $promoCode = if ($lan.Length -gt 4) { $col + $lan[4].ToString().ToUpper() } else { $null }

    # En passant capture
    if ($piece -eq "${col}P" -and $state.EnPassant -ne '-' -and $dst -eq $state.EnPassant) {
        $epDelta = if ($col -eq 'w') { -1 } else { 1 }
        $epRank = [int]::Parse($dst[1].ToString()) + $epDelta
        $sqs.Remove([string]$dst[0] + $epRank) | Out-Null
    }

    # Castling: move the rook
    if ($piece -eq 'wK' -and $src -eq 'e1') {
        if ($dst -eq 'g1') { $sqs['f1'] = 'wR'; $sqs.Remove('h1') | Out-Null }
        if ($dst -eq 'c1') { $sqs['d1'] = 'wR'; $sqs.Remove('a1') | Out-Null }
    }
    if ($piece -eq 'bK' -and $src -eq 'e8') {
        if ($dst -eq 'g8') { $sqs['f8'] = 'bR'; $sqs.Remove('h8') | Out-Null }
        if ($dst -eq 'c8') { $sqs['d8'] = 'bR'; $sqs.Remove('a8') | Out-Null }
    }

    # Move the piece (capture destination first)
    $sqs.Remove($src) | Out-Null
    $sqs.Remove($dst) | Out-Null
    $sqs[$dst] = if ($promoCode) { $promoCode } else { $piece }

    # Update en passant square
    $state.EnPassant = '-'
    $srcR = [int]::Parse($src[1].ToString())
    $dstR = [int]::Parse($dst[1].ToString())
    if ($piece -eq 'wP' -and $srcR -eq 2 -and $dstR -eq 4) { $state.EnPassant = [string]$src[0] + '3' }
    if ($piece -eq 'bP' -and $srcR -eq 7 -and $dstR -eq 5) { $state.EnPassant = [string]$src[0] + '6' }

    # Update castling rights
    $c = $state.Castling
    if ($src -eq 'e1' -or $src -eq 'h1' -or $dst -eq 'h1') { $c = $c -replace 'K', '' }
    if ($src -eq 'e1' -or $src -eq 'a1' -or $dst -eq 'a1') { $c = $c -replace 'Q', '' }
    if ($src -eq 'e8' -or $src -eq 'h8' -or $dst -eq 'h8') { $c = $c -replace 'k', '' }
    if ($src -eq 'e8' -or $src -eq 'a8' -or $dst -eq 'a8') { $c = $c -replace 'q', '' }
    $state.Castling = if ($c) { $c } else { '-' }

    $state.Turn = if ($col -eq 'w') { 'b' } else { 'w' }
    $state.HalfMove++
    if ($col -eq 'b') { $state.FullMove++ }
    return $state
}

# Serialize the board state back to a FEN string.
function Get-Fen($state) {
    $sqs  = $state.Squares
    $rows = @()
    for ($r = 7; $r -ge 0; $r--) {
        $row = ''; $empty = 0
        for ($f = 0; $f -le 7; $f++) {
            $sq = [string]'abcdefgh'[$f] + ($r + 1)
            if ($sqs.ContainsKey($sq)) {
                if ($empty -gt 0) { $row += $empty.ToString(); $empty = 0 }
                $p  = $sqs[$sq]
                $pc = $p[1].ToString()
                $row += if ($p[0] -eq 'w') { $pc.ToUpper() } else { $pc.ToLower() }
            } else { $empty++ }
        }
        if ($empty -gt 0) { $row += $empty.ToString() }
        $rows += $row
    }
    $castling = if ($state.Castling -and $state.Castling.Length -gt 0) { $state.Castling } else { '-' }
    $ep       = if ($state.EnPassant) { $state.EnPassant } else { '-' }
    return ($rows -join '/') + ' ' + $state.Turn + ' ' + $castling + ' ' + $ep + ' ' + $state.HalfMove + ' ' + $state.FullMove
}

# Walk $targetPly SAN moves from $startFen and return the resulting FEN.
# Returns $null if too many SAN parse errors are encountered (≥3 failures).
function Get-FenAtPly([string]$startFen, $sans, [int]$targetPly) {
    $board  = New-BoardFromFen $startFen
    $errors = 0
    for ($i = 0; $i -lt $targetPly -and $i -lt $sans.Count; $i++) {
        $lan = Convert-SanToLan $board $sans[$i]
        if ($null -eq $lan) {
            $errors++
            if ($errors -ge 3) { return $null }
            continue
        }
        try {
            $board = Apply-LanToState $board $lan
        } catch {
            throw "PLY=$i SAN='$($sans[$i])' LAN='$lan' apply error: $($_.Exception.Message)"
        }
    }
    if ($errors -gt 0) { return $null }
    return Get-Fen $board
}

# ── PGN parser ────────────────────────────────────────────────────────────────
function Parse-PgnGames([string]$path) {
    $games   = [System.Collections.Generic.List[hashtable]]::new()
    $hdrs    = [System.Collections.Generic.Dictionary[string,string]]::new()
    $moveBuf = [System.Text.StringBuilder]::new()
    $inMoves = $false

    foreach ($raw in Get-Content -Path $path -Encoding UTF8) {
        $line = $raw.Trim()
        if ($line -match '^\[(\w+)\s+"(.*)"\]') {
            if ($inMoves -and $moveBuf.Length -gt 0) {
                $games.Add(@{ Headers = [hashtable]$hdrs; MoveText = $moveBuf.ToString() })
                $hdrs    = [System.Collections.Generic.Dictionary[string,string]]::new()
                $moveBuf = [System.Text.StringBuilder]::new()
                $inMoves = $false
            }
            $hdrs[$Matches[1]] = $Matches[2]
        } elseif ($line -ne '') {
            $inMoves = $true
            [void]$moveBuf.Append(' ')
            [void]$moveBuf.Append($line)
        } elseif ($inMoves) {
            if ($moveBuf.Length -gt 0) {
                $games.Add(@{ Headers = [hashtable]$hdrs; MoveText = $moveBuf.ToString() })
            }
            $hdrs    = [System.Collections.Generic.Dictionary[string,string]]::new()
            $moveBuf = [System.Text.StringBuilder]::new()
            $inMoves = $false
        }
    }
    if ($inMoves -and $moveBuf.Length -gt 0) {
        $games.Add(@{ Headers = [hashtable]$hdrs; MoveText = $moveBuf.ToString() })
    }
    return $games
}

# Parse movetext into an ordered list of PSCustomObjects: { San, Score }.
# Score is from the perspective of the side that just moved (as annotated by cutechess).
# A move with no following annotation gets Score = $null.
function Get-MovesAndScores([string]$moveText) {
    $result     = [System.Collections.Generic.List[PSCustomObject]]::new()
    $clean      = $moveText -replace '\d+\.+', ' ' -replace '(1-0|0-1|1/2-1/2|\*)', ''
    $pendingSan = $null

    foreach ($m in [regex]::Matches($clean, '\{[^}]*\}|[^\s{}]+')) {
        $tok = $m.Value
        if ($tok.StartsWith('{')) {
            $score = $null
            if ($tok -match '([+-]?\d+(?:\.\d+)?)/\d+') { $score = [double]$Matches[1] }
            if ($pendingSan) {
                $result.Add([PSCustomObject]@{ San = $pendingSan; Score = $score })
                $pendingSan = $null
            }
        } else {
            if ($pendingSan) { $result.Add([PSCustomObject]@{ San = $pendingSan; Score = $null }) }
            $pendingSan = $tok
        }
    }
    if ($pendingSan) { $result.Add([PSCustomObject]@{ San = $pendingSan; Score = $null }) }
    return ,$result
}

# ── Engine eval runner ────────────────────────────────────────────────────────
function Get-EngineEval([string]$fen) {
    $psi                        = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName               = $Java
    $psi.Arguments              = "-jar `"$VexJar`""
    $psi.UseShellExecute        = $false
    $psi.RedirectStandardInput  = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    $psi.CreateNoWindow         = $true

    $proc = [System.Diagnostics.Process]::Start($psi)
    $proc.StandardInput.Write("uci`nisready`nposition fen $fen`neval`nquit`n")
    $proc.StandardInput.Close()
    $out = $proc.StandardOutput.ReadToEnd()
    $proc.WaitForExit()

    $lines = ($out -split "`n") |
             Where-Object { $_ -match '^info string' } |
             ForEach-Object { ($_ -replace '^info string\s*', '').TrimEnd() }
    return $lines -join "`n"
}

function Parse-EvalMetrics([string]$evalText) {
    $materialMg = $null
    $mobilityMg = $null
    $kingSafetyMg = $null
    $finalCp = $null

    if ($evalText -match 'material\+PST\s+mg=\s*([+-]?\d+)') { $materialMg = [int]$Matches[1] }
    if ($evalText -match 'mobility\s+mg=\s*([+-]?\d+)')      { $mobilityMg = [int]$Matches[1] }
    if ($evalText -match 'king safety\s+mg=\s*([+-]?\d+)')   { $kingSafetyMg = [int]$Matches[1] }
    if ($evalText -match 'final\s+([+-]?\d+)\s+cp')          { $finalCp = [int]$Matches[1] }

    return [PSCustomObject]@{
        MaterialPstMg = $materialMg
        MobilityMg    = $mobilityMg
        KingSafetyMg  = $kingSafetyMg
        FinalCp       = $finalCp
    }
}

function Get-PhaseFromPly([int]$ply) {
    $fullMove = [Math]::Floor($ply / 2) + 1
    if ($fullMove -lt 15) { return 'opening' }
    if ($fullMove -le 35) { return 'middlegame' }
    return 'late'
}

function Get-WhitePovScore($moves, [int]$ply) {
    $raw = $moves[$ply].Score
    if ($null -eq $raw) { return $null }
    if ($ply % 2 -eq 0) { return [double]$raw }
    return -([double]$raw)
}

# ── Main ──────────────────────────────────────────────────────────────────────
Write-Host "Parsing PGN: $PgnFile"
$allGames = Parse-PgnGames $PgnFile
Write-Host "Total games parsed: $($allGames.Count)"

$whiteLosses = $allGames | Where-Object {
    $h = $_.Headers
    $h.ContainsKey('White') -and $h['White'] -eq 'NEW' -and
    $h.ContainsKey('Result') -and $h['Result'] -eq '0-1' -and
    $h.ContainsKey('FEN')
}
Write-Host "NEW-as-White losses with FEN tag: $($whiteLosses.Count)"
Write-Host "Opening ply skip: $SkipOpenPly (= $([int]($SkipOpenPly/2)) full moves each side)"

if ($whiteLosses.Count -eq 0) { Write-Warning "No NEW-as-White losses found."; exit 0 }

# Rank games by the largest White eval collapse that occurs after $SkipOpenPly plies.
$ranked = foreach ($game in $whiteLosses) {
    $ms = Get-MovesAndScores $game.MoveText
    if ($ms.Count -le $SkipOpenPly) { continue }   # game too short to have post-opening collapse

    $peakWpov  = [double]::MinValue
    $worstDrop = 0.0
    $troughPly = -1

    for ($i = 0; $i -lt $ms.Count; $i++) {
        $raw = $ms[$i].Score
        if ($null -eq $raw) { continue }
        # cutechess annotates from the side that just MOVED: even ply=White moved, odd ply=Black moved
        $wScore = if ($i % 2 -eq 0) { $raw } else { -$raw }
        if ($wScore -gt $peakWpov) { $peakWpov = $wScore }
        if ($i -ge $SkipOpenPly) {
            $drop = $peakWpov - $wScore
            if ($drop -gt $worstDrop) { $worstDrop = $drop; $troughPly = $i }
        }
    }

    if ($troughPly -lt 0) { continue }   # no post-opening collapse in this game

    # Pre-blunder ply: one before the trough, floored at $SkipOpenPly
    $prePly = [Math]::Max($SkipOpenPly, $troughPly - 1)

    [PSCustomObject]@{
        Game       = $game
        Drop       = [Math]::Round($worstDrop, 2)
        TroughPly  = $troughPly
        PrePly     = $prePly
        TotalPly   = $ms.Count
        Moves      = $ms
        Sans       = [string[]]($ms | ForEach-Object { $_.San })
        StartFen   = $game.Headers['FEN']
    }
}

$top = @($ranked) | Sort-Object -Property Drop -Descending | Select-Object -First $TopN

if ($top.Count -eq 0) {
    Write-Warning "No games had a post-opening (ply >= $SkipOpenPly) eval collapse. Try reducing -SkipOpenPly."
    exit 0
}
Write-Host "Games with post-opening collapse found: $($top.Count) (showing top $TopN)"

$outDir = Split-Path $OutFile -Parent
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$skipDir = Split-Path $SkippedRawFile -Parent
if ($skipDir -and -not (Test-Path $skipDir)) { New-Item -ItemType Directory -Path $skipDir | Out-Null }

@(
    "=== skipped positions (raw context) ===",
    "PGN: $PgnFile",
    "Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    ""
) | Set-Content -Path $SkippedRawFile -Encoding UTF8

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("=== explain_eval: NEW-as-White losses — king-safety SPRT (mid-game FENs) ===")
[void]$sb.AppendLine("PGN          : $PgnFile")
[void]$sb.AppendLine("Date         : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
[void]$sb.AppendLine("Top-N        : $TopN")
[void]$sb.AppendLine("Open ply skip: $SkipOpenPly  (collapses in first $([int]($SkipOpenPly/2)) moves each side are ignored)")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("Patterns to scan:")
[void]$sb.AppendLine("  - king safety  mg < 50 cp with attackers >= 3  → scale too low")
[void]$sb.AppendLine("  - tempo        consistently negative for White  → tempo regression")
[void]$sb.AppendLine("  - pawn shield  near 0 with open files near king → shield not firing")
[void]$sb.AppendLine("  - mobility     White negative                   → over-penalised near own king")
[void]$sb.AppendLine("")

$anchorN = [Math]::Min($AnchorValidationN, $top.Count)
if ($anchorN -gt 0) {
    [void]$sb.AppendLine("Anchor Validation Sample ($anchorN games)")
    [void]$sb.AppendLine("  Goal: confirm pre-blunder ply/trough ply anchors against PGN SAN + score annotations")
    for ($ai = 0; $ai -lt $anchorN; $ai++) {
        $p = $top[$ai]
        $troughSan = $p.Moves[$p.TroughPly].San
        $preSan = $p.Moves[$p.PrePly].San
        $preScore = Get-WhitePovScore $p.Moves $p.PrePly
        $troughScore = Get-WhitePovScore $p.Moves $p.TroughPly
        [void]$sb.AppendLine("  - Position $($ai + 1): prePly=$($p.PrePly) SAN='$preSan' scoreW=$preScore ; troughPly=$($p.TroughPly) SAN='$troughSan' scoreW=$troughScore ; drop=$($p.Drop)")
    }
    [void]$sb.AppendLine("")
}

$idx = 1
foreach ($pos in $top) {
    Write-Host "[$idx/$($top.Count)] drop=$($pos.Drop) cp  collapse=ply$($pos.TroughPly)/$($pos.TotalPly)  pre-blunder=ply$($pos.PrePly)  walking board..."
    try {
        $preFen  = Get-FenAtPly $pos.StartFen $pos.Sans $pos.PrePly
        $fenNote = if ($null -ne $preFen) {
            "(ply $($pos.PrePly) pre-blunder)"
        } else {
            $preFen = $pos.StartFen
            "(FALLBACK: SAN parse error — using game start FEN)"
        }
        Write-Host "    FEN [$fenNote]: $preFen"
        Write-Host "    running eval..."
        $evalText = Get-EngineEval $preFen
        $preMetrics = Parse-EvalMetrics $evalText

        $temporalSection = @()
        if ($idx -le $TemporalSampleN) {
            $temporalSection += ""
            $temporalSection += "Temporal Attribution (sampled anchors: pre, pre-5, pre-10)"

            $snapshots = [System.Collections.Generic.List[PSCustomObject]]::new()
            foreach ($off in @(-10, -5, 0)) {
                $targetPly = [Math]::Max($SkipOpenPly, $pos.PrePly + $off)
                $snapFen = if ($off -eq 0) { $preFen } else { Get-FenAtPly $pos.StartFen $pos.Sans $targetPly }
                if ($null -eq $snapFen) { $snapFen = $pos.StartFen }
                $snapEval = if ($off -eq 0) { $evalText } else { Get-EngineEval $snapFen }
                $m = Parse-EvalMetrics $snapEval
                $stm = ($snapFen -split '\s+')[1]
                $snapshots.Add([PSCustomObject]@{
                    Offset = $off
                    Ply = $targetPly
                    SideToMove = $stm
                    Phase = Get-PhaseFromPly $targetPly
                    MaterialPstMg = $m.MaterialPstMg
                    MobilityMg = $m.MobilityMg
                    KingSafetyMg = $m.KingSafetyMg
                    FinalCp = $m.FinalCp
                })
            }

            $ordered = @($snapshots) | Sort-Object Ply
            $cross = $ordered | Where-Object { $null -ne $_.MaterialPstMg -and $_.MaterialPstMg -lt -50 } | Select-Object -First 1
            if ($null -ne $cross) {
                $temporalSection += "  first observed material+PST MG < -50 cp: ply=$($cross.Ply), phase=$($cross.Phase), side-to-move=$($cross.SideToMove), mg=$($cross.MaterialPstMg)"
            } else {
                $temporalSection += "  first observed material+PST MG < -50 cp: not observed in sampled anchors"
            }

            foreach ($s in $ordered) {
                $temporalSection += "  anchor off=$($s.Offset), ply=$($s.Ply), phase=$($s.Phase), stm=$($s.SideToMove), material+PST MG=$($s.MaterialPstMg), mobility MG=$($s.MobilityMg), king safety MG=$($s.KingSafetyMg), final=$($s.FinalCp)"
            }
        }

        [void]$sb.AppendLine("━━━ Position $idx ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        [void]$sb.AppendLine("  Eval collapse  : $($pos.Drop) cp   (peak → trough, White-positive)")
        [void]$sb.AppendLine("  Trough at ply  : $($pos.TroughPly)  of $($pos.TotalPly) total plies")
        [void]$sb.AppendLine("  Pre-blunder ply: $($pos.PrePly)  (position evaluated below)")
        [void]$sb.AppendLine("  FEN            : $preFen  $fenNote")
        [void]$sb.AppendLine("")
        [void]$sb.AppendLine($(if ($evalText) { $evalText } else { "  [no eval output — check engine JAR]" }))
        foreach ($line in $temporalSection) {
            [void]$sb.AppendLine($line)
        }
        [void]$sb.AppendLine("")
    } catch {
        Write-Warning "Position $idx skipped — error: $_"
        [void]$sb.AppendLine("━━━ Position $idx ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        [void]$sb.AppendLine("  [SKIPPED — error during processing: $_]")
        [void]$sb.AppendLine("")

        @(
            "--- Position $idx ---",
            "Error: $_",
            "Drop: $($pos.Drop)",
            "TroughPly: $($pos.TroughPly)",
            "PrePly: $($pos.PrePly)",
            "StartFen: $($pos.StartFen)",
            "MoveText:",
            $pos.Game.MoveText,
            ""
        ) | Add-Content -Path $SkippedRawFile -Encoding UTF8
    }
    # Flush to disk every 25 positions so a crash doesn't lose all data
    if ($idx % 25 -eq 0) { $sb.ToString() | Set-Content -Path $OutFile -Encoding UTF8 }
    $idx++
}

$sb.ToString() | Set-Content -Path $OutFile -Encoding UTF8
Write-Host ""
Write-Host "Done. Results written to: $OutFile"
Write-Host "Skipped-position context written to: $SkippedRawFile"