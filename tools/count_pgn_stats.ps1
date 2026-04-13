param([string]$PgnFile)
$headers = Get-Content $PgnFile | Where-Object { $_ -match '^\[(White|Black|Result) ' }
$newWins = 0; $newLoss = 0; $newDraw = 0
for ($i = 0; $i -lt ($headers.Count - 2); $i += 3) {
    $w = $headers[$i]; $b = $headers[$i+1]; $r = $headers[$i+2]
    if     ($w -match 'Vex-new' -and $r -match '"1-0"')    { $newWins++ }
    elseif ($b -match 'Vex-new' -and $r -match '"0-1"')    { $newWins++ }
    elseif ($w -match 'Vex-new' -and $r -match '"0-1"')    { $newLoss++ }
    elseif ($b -match 'Vex-new' -and $r -match '"1-0"')    { $newLoss++ }
    elseif ($r -match '"1/2-1/2"') { $newDraw++ }
}
$total = $newWins + $newLoss + $newDraw
$score = if ($total -gt 0) { ($newWins + 0.5*$newDraw) / $total } else { 0 }
Write-Host "Vex-new: W=$newWins L=$newLoss D=$newDraw Total=$total Score=$([Math]::Round($score,4))"
