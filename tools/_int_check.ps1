#Requires -Version 7
param()
for ($sq = 0; $sq -lt 64; $sq++) {
    $ps = [int]($sq / 8)
    $fl = [int][Math]::Floor($sq / 8)
    if ($ps -ne $fl) { Write-Host "sq $sq : [int](sq/8)=$ps vs floor=$fl" }
}
Write-Host "Done"
