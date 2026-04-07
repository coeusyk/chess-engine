#Requires -Version 7
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Source the board tracker functions from generate_texel_corpus.ps1
$src = Get-Content "$PSScriptRoot\generate_texel_corpus.ps1" -Raw
# Extract just the functions (everything between the board tracker comments)
# We'll define them inline by invoking the function definitions

# --- Copy board tracker functions directly ---

function New-ChessBoard([string]$fenStr) {
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

function Can-SliderReach([char[]]$sq, [int]$from, [int]$to, [int[]]$dirs) {
    foreach ($d in $dirs) {
        $cur = $from + $d
        while ($cur -ge 0 -and $cur -lt 64) {
            $prevF = ($cur - $d) % 8; $curF = $cur % 8
            if ([Math]::Abs($d % 8) -eq 1 -and [Math]::Abs($curF - $prevF) -ne 1) { break }
            if ([Math]::Abs($d) -in @(7,9) -and [Math]::Abs($cur % 8 - ($cur - $d) % 8) -ne 1) { break }
            if ($cur -eq $to) { return $true }
            if ($sq[$cur] -ne ' ') { break }
            $cur += $d
        }
    }
    return $false
}

function Find-SourceSquare($brd, [char]$piece, [int]$toSq, [string]$disam) {
    $isWhite = $piece -ge 'A' -and $piece -le 'Z'
    $toFile  = $toSq % 8
    $toRank  = ($toSq -shr 3)
    $candidates = @()

    for ($s = 0; $s -lt 64; $s++) {
        if ($brd.sq[$s] -cne $piece) { continue }
        $sFile = $s % 8; $sRank = ($s -shr 3)

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

    if ($disam -match '^[a-h]$') {
        $f = [int][char]$disam[0] - [int][char]'a'
        foreach ($c in $candidates) { if ($c % 8 -eq $f) { return $c } }
    }
    if ($disam -match '^[1-8]$') {
        $r = 8 - [int]$disam[0] + 48
        foreach ($c in $candidates) { if (($c -shr 3) -eq $r) { return $c } }
    }
    if ($disam -match '^[a-h][1-8]$') {
        $f = [int][char]$disam[0] - [int][char]'a'
        $r = 8 - [int]$disam[1] + 48
        foreach ($c in $candidates) { if (($c % 8 -eq $f) -and (($c -shr 3) -eq $r)) { return $c } }
    }
    return $candidates[0]
}

function Apply-San($brd, [string]$san) {
    $san = $san -replace '[+#!?]',''
    if ($san -eq '') { return $true }
    $side = $brd.side

    if ($san -eq 'O-O') {
        if ($side -eq 'w') { $brd.sq[60]=' '; $brd.sq[62]='K'; $brd.sq[63]=' '; $brd.sq[61]='R' }
        else               { $brd.sq[4]=' ';  $brd.sq[6]='k';  $brd.sq[7]=' ';  $brd.sq[5]='r' }
        if ($side -eq 'w') { $brd.castling = $brd.castling -replace '[KQ]','' }
        else               { $brd.castling = $brd.castling -replace '[kq]','' }
        if ($brd.castling -eq '') { $brd.castling = '-' }
        $brd.side = if ($side -eq 'w') { 'b' } else { 'w' }; $brd.ep = '-'; return $true
    }
    if ($san -eq 'O-O-O') {
        if ($side -eq 'w') { $brd.sq[60]=' '; $brd.sq[58]='K'; $brd.sq[56]=' '; $brd.sq[59]='R' }
        else               { $brd.sq[4]=' ';  $brd.sq[2]='k';  $brd.sq[0]=' ';  $brd.sq[3]='r' }
        if ($side -eq 'w') { $brd.castling = $brd.castling -replace '[KQ]','' }
        else               { $brd.castling = $brd.castling -replace '[kq]','' }
        if ($brd.castling -eq '') { $brd.castling = '-' }
        $brd.side = if ($side -eq 'w') { 'b' } else { 'w' }; $brd.ep = '-'; return $true
    }

    $brd.ep = '-'

    $promoPiece = ''
    if ($san -match '=([QRBNqrbn])$') {
        $promoPiece = $Matches[1]
        if ($side -eq 'b') { $promoPiece = $promoPiece.ToLower() }
        else               { $promoPiece = $promoPiece.ToUpper() }
        $san = $san -replace '=[QRBNqrbn]$',''
    }

    if ($san[0] -ge 'a' -and $san[0] -le 'h') {
        $destStr = $san.Substring($san.Length - 2)
        $toFile  = [int][char]$destStr[0] - [int][char]'a'
        $toRank  = [int][char]$destStr[1] - 48
        $toSq    = (8 - $toRank) * 8 + $toFile
        $myPawn  = if ($side -eq 'w') { 'P' } else { 'p' }
        $dir     = if ($side -eq 'w') { 1 } else { -1 }
        $fromSq  = -1

        if ($san.Length -eq 2) {
            $r1 = $toRank + $dir * (-1)
            if ($r1 -ge 1 -and $r1 -le 8) {
                $s1 = (8 - $r1) * 8 + $toFile
                if ($brd.sq[$s1] -ceq $myPawn) { $fromSq = $s1 }
                else {
                    $r2 = $toRank + $dir * (-2)
                    if ($r2 -ge 1 -and $r2 -le 8) {
                        $s2 = (8 - $r2) * 8 + $toFile
                        if ($brd.sq[$s2] -ceq $myPawn) { $fromSq = $s2 }
                    }
                }
            }
        } else {
            $fromFile = [int][char]$san[0] - [int][char]'a'
            $fromRank = $toRank + $dir * (-1)
            $fromSq   = (8 - $fromRank) * 8 + $fromFile
            if ($brd.sq[$toSq] -eq ' ') {
                $epCapSq = (8 - $fromRank) * 8 + $toFile
                $brd.sq[$epCapSq] = ' '
            }
        }

        if ($fromSq -lt 0 -or $fromSq -ge 64) { $brd.side = if ($side -eq 'w') {'b'} else {'w'}; return $false }

        if ([Math]::Abs($toSq - $fromSq) -eq 16) {
            $epRank  = if ($side -eq 'w') { $toRank - 1 } else { $toRank + 1 }
            $epChar  = [char]([int][char]'a' + $toFile)
            $brd.ep  = "$epChar$epRank"
        }

        $brd.sq[$fromSq] = ' '
        $brd.sq[$toSq]   = if ($promoPiece -ne '') { $promoPiece } else { $myPawn }
        if ($toSq -eq 0)  { $brd.castling = $brd.castling -replace 'q','' }
        if ($toSq -eq 7)  { $brd.castling = $brd.castling -replace 'k','' }
        if ($toSq -eq 56) { $brd.castling = $brd.castling -replace 'Q','' }
        if ($toSq -eq 63) { $brd.castling = $brd.castling -replace 'K','' }
        if ($brd.castling -eq '') { $brd.castling = '-' }
        $brd.side = if ($side -eq 'w') { 'b' } else { 'w' }
        return $true
    }

    $pieceChar = if ($side -eq 'w') { [char]$san[0] } else { [char]($san[0].ToString().ToLower()) }
    $rest      = ($san.Substring(1) -replace 'x','')
    $dest      = $rest.Substring($rest.Length - 2)
    $disam     = if ($rest.Length -gt 2) { $rest.Substring(0, $rest.Length - 2) } else { '' }
    $toFile    = [int][char]$dest[0] - [int][char]'a'
    $toRank    = [int][char]$dest[1] - 48
    $toSq      = (8 - $toRank) * 8 + $toFile

    $fromSq = Find-SourceSquare $brd $pieceChar $toSq $disam
    if ($fromSq -lt 0) { $brd.side = if ($side -eq 'w') {'b'} else {'w'}; return $false }
    
    # Debug Nxd4
    if ($san -eq 'Nxd4') {
        Write-Warning "Nxd4: pieceChar=$pieceChar fromSq=$fromSq toSq=$toSq sq[$fromSq]=$($brd.sq[$fromSq])"
    }

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

# --- Trace game 10 (Ng3 failure) ---
$startFen = "rnbqkbnr/1pp1p1pp/p4p2/3p4/5P2/P2P4/1PP1P1PP/RNBQKBNR w KQkq - 0 1"
$board = New-ChessBoard $startFen
Write-Host "Start FEN: $startFen"
Write-Host ""

$SANs = @('Nf3','Nc6','Nc3','e5','fxe5','fxe5','e4','d4','Nd5','Nf6','Nxf6+','Qxf6','Be2','Qd8','Bg5','Be7','Bd2','Bg4','Nxd4','Bxe2','Nxe2','O-O','Ng3')
foreach ($san in $SANs) {
    $side = $board.side
    # Show board before applying
    $nCount = ($board.sq | Where-Object { $_ -eq 'N' }).Count
    if ($san -eq 'Nxd4' -or $san -eq 'Bxe2') {
        Write-Host "  [Pre-$san] White N count=$nCount"
        for ($sq2 = 0; $sq2 -lt 64; $sq2++) {
            $pc = $board.sq[$sq2]
            if ($pc -in @('N','n','B','b')) {
                $file = [char]([int][char]'a' + ($sq2 % 8))
                $rank = 8 - ($sq2 -shr 3)
                Write-Host "    sq$sq2 ($file$rank): $pc"
            }
        }
    }
    $ok = Apply-San $board $san
    $fen = Board-ToFen $board
    $nCount2 = ($board.sq | Where-Object { $_ -eq 'N' }).Count
    Write-Host "[$side] $san -> ok=$ok | N_count=$nCount2 | $fen"
    if (-not $ok) {
        Write-Host "  *** FAILED *** searching for piece"
        Write-Host "  Board pieces:"
        for ($sq2 = 0; $sq2 -lt 64; $sq2++) {
            $pc = $board.sq[$sq2]
            if ($pc -ne ' ') {
                $file = [char]([int][char]'a' + ($sq2 % 8))
                $rank = 8 - ($sq2 -shr 3)
                Write-Host "    sq$sq2 ($file$rank): $pc"
            }
        }
        break
    }
}
