#!/usr/bin/env pwsh
# One-shot aggregation of explain_h0_king_safety_all280.txt
param([string]$InFile = "tools\output\explain_h0_king_safety_all280.txt")

$lines = Get-Content $InFile

$positions = [System.Collections.Generic.List[PSCustomObject]]::new()
$cur = $null

foreach ($line in $lines) {
    if ($line -match '━━━ Position (\d+)') {
        if ($cur) { $positions.Add([PSCustomObject]$cur) }
        $cur = @{ Pos=$Matches[1]; Drop=0; Phase=0; KS=0; Mob=0; Mat=0; PawnStr=0; Passed=0; Tempo=0; Hanging=0; Final=0 }
    }
    if (!$cur) { continue }
    if ($line -match 'Eval collapse\s*:\s*([\d.]+)') { $cur.Drop = [double]$Matches[1] }
    if ($line -match 'king safety\s+mg=\s*([+-]?\d+)')  { $cur.KS      = [int]$Matches[1] }
    if ($line -match 'mobility\s+mg=\s*([+-]?\d+)')       { $cur.Mob     = [int]$Matches[1] }
    if ($line -match 'material\+PST\s+mg=\s*([+-]?\d+)')  { $cur.Mat     = [int]$Matches[1] }
    if ($line -match 'pawn structure\s+mg=\s*([+-]?\d+)') { $cur.PawnStr = [int]$Matches[1] }
    if ($line -match 'passed pawns\s+mg=\s*([+-]?\d+)')   { $cur.Passed  = [int]$Matches[1] }
    if ($line -match 'tempo\s+([+-]?\d+) cp')             { $cur.Tempo   = [int]$Matches[1] }
    if ($line -match 'hanging\s+([+-]?\d+) cp')           { $cur.Hanging = [int]$Matches[1] }
    if ($line -match 'final\s+([+-]?\d+) cp')             { $cur.Final   = [int]$Matches[1] }
    if ($line -match 'totals\s+mg=\s*[+-]?\d+\s+eg=\s*[+-]?\d+\s+phase=(\d+)/24') { $cur.Phase = [int]$Matches[1] }
}
if ($cur) { $positions.Add([PSCustomObject]$cur) }

$n = $positions.Count
Write-Host "=== AGGREGATE: $n positions ==="
Write-Host ""

# Phase breakdown
Write-Host "-- GAME PHASE distribution --"
$eg  = ($positions | Where-Object { $_.Phase -le 4 }).Count
$mid = ($positions | Where-Object { $_.Phase -ge 11 }).Count
$tr  = ($positions | Where-Object { $_.Phase -ge 5 -and $_.Phase -le 10 }).Count
Write-Host "  Midgame (11-24): $mid  |  Transition (5-10): $tr  |  Endgame (0-4): $eg"
Write-Host ""

# King safety
Write-Host "-- KING SAFETY mg --"
$ks = $positions.KS
$ksMean  = [Math]::Round(($ks | Measure-Object -Average).Average, 1)
$ksMin   = ($ks | Measure-Object -Minimum).Minimum
$ksMax   = ($ks | Measure-Object -Maximum).Maximum
$ksVneg  = ($positions | Where-Object { $_.KS -lt -100 }).Count
$ksneg   = ($positions | Where-Object { $_.KS -ge -100 -and $_.KS -lt -30 }).Count
$ksnear  = ($positions | Where-Object { $_.KS -ge -30 -and $_.KS -le 30 }).Count
$kspos   = ($positions | Where-Object { $_.KS -gt 30 }).Count
Write-Host "  Mean=$ksMean  Min=$ksMin  Max=$ksMax"
Write-Host "  < -100: $ksVneg  |  -100..-30: $ksneg  |  -30..+30: $ksnear  |  > +30: $kspos"
Write-Host ""

# Mobility
Write-Host "-- MOBILITY mg --"
$mob = $positions.Mob
$mobMean = [Math]::Round(($mob | Measure-Object -Average).Average, 1)
$mobVneg = ($positions | Where-Object { $_.Mob -lt -100 }).Count
$mobneg  = ($positions | Where-Object { $_.Mob -ge -100 -and $_.Mob -lt -20 }).Count
$mobnear = ($positions | Where-Object { $_.Mob -ge -20 -and $_.Mob -le 20 }).Count
$mobpos  = ($positions | Where-Object { $_.Mob -gt 20 }).Count
Write-Host "  Mean=$mobMean"
Write-Host "  < -100: $mobVneg  |  -100..-20: $mobneg  |  -20..+20: $mobnear  |  > +20: $mobpos"
Write-Host ""

# Material
Write-Host "-- MATERIAL+PST mg --"
$mat = $positions.Mat
$matMean = [Math]::Round(($mat | Measure-Object -Average).Average, 1)
$matVneg = ($positions | Where-Object { $_.Mat -lt -300 }).Count
$matneg  = ($positions | Where-Object { $_.Mat -ge -300 -and $_.Mat -lt -50 }).Count
$matnear = ($positions | Where-Object { $_.Mat -ge -50 -and $_.Mat -le 50 }).Count
$matpos  = ($positions | Where-Object { $_.Mat -gt 50 }).Count
Write-Host "  Mean=$matMean"
Write-Host "  < -300: $matVneg  |  -300..-50: $matneg  |  -50..+50: $matnear  |  > +50: $matpos"
Write-Host ""

# Final eval
Write-Host "-- FINAL EVAL (cp, side-to-move relative) --"
$fe = $positions.Final
$feMean = [Math]::Round(($fe | Measure-Object -Average).Average, 1)
$feMin  = ($fe | Measure-Object -Minimum).Minimum
$feMax  = ($fe | Measure-Object -Maximum).Maximum
Write-Host "  Mean=$feMean  Min=$feMin  Max=$feMax"
Write-Host ""

# Dominant term analysis: what is the biggest negative contributor per position?
Write-Host "-- PRIMARY CAUSE of negative eval (biggest negative mg term) --"
$causes = @{ KS=0; Mob=0; Mat=0; Pawn=0; Other=0 }
foreach ($p in $positions) {
    $ksV   = $p.KS
    $mobV  = $p.Mob
    $matV  = $p.Mat
    $pawnV = $p.PawnStr
    $worst = [Math]::Min([Math]::Min($ksV, $mobV), [Math]::Min($matV, $pawnV))
    if ($worst -ge -30) { $causes.Other++; continue }
    if ($worst -eq $ksV)   { $causes.KS++ }
    elseif ($worst -eq $matV)  { $causes.Mat++ }
    elseif ($worst -eq $mobV)  { $causes.Mob++ }
    elseif ($worst -eq $pawnV) { $causes.Pawn++ }
    else                       { $causes.Other++ }
}
Write-Host "  KS primary: $($causes.KS)  |  Material primary: $($causes.Mat)  |  Mobility primary: $($causes.Mob)  |  Pawn primary: $($causes.Pawn)  |  Other/None: $($causes.Other)"
Write-Host ""

# Positions where KS is bad AND phase is midgame (where KS should fire)
Write-Host "-- KS < -100 in MIDGAME (phase 11+) -- high-confidence KS failures --"
$ksBadMid = $positions | Where-Object { $_.KS -lt -100 -and $_.Phase -ge 11 } | Sort-Object KS
$ksBadMid | Select-Object Pos,Drop,Phase,KS,Mob,Mat,Final | Format-Table -AutoSize

Write-Host ""
Write-Host "-- KS < -100 in ENDGAME (phase < 5) -- KS firing when it shouldn't --"
$ksBadEg = $positions | Where-Object { $_.KS -lt -100 -and $_.Phase -lt 5 } | Sort-Object KS
$ksBadEg | Select-Object Pos,Drop,Phase,KS,Mob,Mat,Final | Format-Table -AutoSize

# Mobility deep-dive: positions where mobility drives the loss
Write-Host ""
Write-Host "-- MOB < -80 positions (mobility primary loss driver) --"
$positions | Where-Object { $_.Mob -lt -80 } | Sort-Object Mob | Select-Object Pos,Drop,Phase,KS,Mob,Mat,Final | Format-Table -AutoSize

# Positions where eval is positive despite being a loss (adjudication artifact)
Write-Host ""
Write-Host "-- Positions with final > +20 (engine thinks it's fine, positional death after) --"
$positions | Where-Object { $_.Final -gt 20 } | Sort-Object { -$_.Final } | Select-Object -First 10 Pos,Drop,Phase,KS,Mob,Mat,Final | Format-Table -AutoSize
