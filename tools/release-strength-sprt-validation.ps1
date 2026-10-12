# Synthetic checks only: no engine, Cute Chess or game is launched and no host probe is made.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'release-strength-sprt-common.ps1')
$script:checks = 0
function Assert-Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Validation failed: $Message" }
    $script:checks++
}
function Assert-Rejected([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-Check $rejected $Message
}
function New-FixtureGame([string]$White, [string]$Result, [string]$Fen = 'fixture opening') {
    $black = if ($White -eq 'NEW') { 'OLD' } else { 'NEW' }
    return "[Event `"fixture`"]`n[White `"$White`"]`n[Black `"$black`"]`n[Result `"$Result`"]`n[FEN `"$Fen`"]`n[TimeControl `"5+0.05`"]`n`n1. e4 e5 $Result`n`n"
}
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$temp = Join-Path ([IO.Path]::GetTempPath()) ('release-strength-sprt-validation-' + [guid]::NewGuid().ToString('N'))
$oldJava = $env:JAVA; $oldCute = $env:CUTECHESS
New-Item -ItemType Directory -Path (Join-Path $temp 'jdk\bin') | Out-Null
try {
    $config = Get-ReleaseSprtConfig
    # Identities and the experiment's fixed numbers.
    Assert-Check ($config.CandidateSha256 -ceq 'e16163b8fe6f8278161db0d9220883c5d49a4814df0d81f7a6cad901c07cf4e7' -and $config.BaselineSha256 -ceq 'bc41c91b0d3b4c20f6a334ff3f0cc2cf5c04e6820b817b1cfd46e62a640663d3') 'published asset identities'
    Assert-Check ($config.Elo0 -eq 0 -and $config.Elo1 -eq 20 -and $config.Alpha -eq 0.05 -and $config.Beta -eq 0.05 -and $config.TC -ceq '5+0.05' -and $config.Concurrency -eq 6 -and $config.EngineThreads -eq 1) 'proposed SPRT parameters'
    Assert-Check ((@($config.NewOptions) -join ',') -ceq 'Hash=16,PawnHashSize=1,EvalType=Classical,MultiPV=1,Contempt=0,OwnBook=false,SyzygyOnline=false,SyzygyPath=') 'candidate options'
    Assert-Check ((@($config.OldOptions) -join ',') -ceq 'Hash=16,PawnHashSize=1,MultiPV=1,Contempt=0,OwnBook=false,SyzygyOnline=false,SyzygyPath=') 'baseline options omit EvalType'
    Assert-Check ($config.PreregistrationSha256 -cne 'PENDING' -and (Get-Stage6Hash (Join-Path $repoRoot $config.PreregistrationRelativePath)) -ceq $config.PreregistrationSha256) 'preregistration is frozen and matches the committed file'
    $openingPath = Join-Path $repoRoot $config.OpeningRelativePath
    Assert-Check ((Get-Stage6Hash $openingPath) -ceq $config.OpeningSha256) 'committed opening list hash'
    Assert-ReleaseSprtOpenings $openingPath; $script:checks++
    $good = [IO.File]::ReadAllText($openingPath)
    Assert-Rejected { [IO.File]::WriteAllText((Join-Path $temp 'crlf.epd'), $good.Replace("`n", "`r`n")); Assert-ReleaseSprtOpenings (Join-Path $temp 'crlf.epd') } 'reject CRLF opening list'
    Assert-Rejected { [IO.File]::WriteAllText((Join-Path $temp 'short.epd'), ($good -split "`n" | Select-Object -First 9999) -join "`n"); Assert-ReleaseSprtOpenings (Join-Path $temp 'short.epd') } 'reject short opening list'
    Assert-Rejected { $l = @($good.TrimEnd("`n") -split "`n"); $l[1] = $l[0]; [IO.File]::WriteAllText((Join-Path $temp 'dup.epd'), ($l -join "`n") + "`n"); Assert-ReleaseSprtOpenings (Join-Path $temp 'dup.epd') } 'reject duplicate opening'
    Assert-Rejected { $l = @($good.TrimEnd("`n") -split "`n"); $l[0] = $l[0].Replace(' w ', ' b '); [IO.File]::WriteAllText((Join-Path $temp 'black.epd'), ($l -join "`n") + "`n"); Assert-ReleaseSprtOpenings (Join-Path $temp 'black.epd') } 'reject black-to-move opening'

    # Parameters, options and arguments.
    foreach ($name in @('cutechess.exe', 'candidate-v0.6.0.jar', 'baseline-v0.5.7.jar', 'openings.epd', 'jdk\bin\java.exe')) { Set-Content -LiteralPath (Join-Path $temp $name) -Value 'inert fixture' }
    $env:JAVA = Join-Path $temp 'jdk'; $env:CUTECHESS = Join-Path $temp 'cutechess.exe'
    $parameters = Get-ReleaseSprtParameters $temp (Join-Path $temp 'openings.epd')
    Assert-ReleaseSprtParameters $parameters
    foreach ($key in @('Elo0', 'Elo1', 'Alpha', 'Beta', 'BonferroniM', 'MinGames', 'MaxGames', 'TC', 'Concurrency', 'EngineThreads', 'OpeningOrder')) {
        $bad = $parameters.Clone(); $bad[$key] = if ($key -in @('TC', 'OpeningOrder')) { 'random' } else { 999 }
        Assert-Rejected { Assert-ReleaseSprtParameters $bad } "reject changed $key"
    }
    foreach ($key in @('NewOptions', 'OldOptions')) {
        $bad = $parameters.Clone(); $bad[$key] = @('Hash=32')
        Assert-Rejected { Assert-ReleaseSprtParameters $bad } "reject changed $key"
    }
    $bad = $parameters.Clone(); $bad.OldOptions = @($config.NewOptions)
    Assert-Rejected { Assert-ReleaseSprtParameters $bad } 'reject sending EvalType to v0.5.7'
    foreach ($key in @('New', 'Old', 'OpeningsFile', 'LogPath', 'PgnPath')) { $bad = $parameters.Clone(); $bad.Remove($key); Assert-Rejected { Assert-ReleaseSprtParameters $bad } "reject missing $key" }
    $bad = $parameters.Clone(); $bad.LogPath = $bad.PgnPath
    Assert-Rejected { Assert-ReleaseSprtParameters $bad } 'reject colliding evidence paths'
    $manifestPath = Join-Path $temp 'prepared.json'
    & (Join-Path $PSScriptRoot 'sprt.ps1') @parameters -PrepareOnly -ArgumentManifestPath $manifestPath | Out-Null
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    Assert-ReleaseSprtArguments $manifest $parameters (Join-Path $env:JAVA 'bin\java.exe') $env:CUTECHESS
    Assert-Check ($manifest.Arguments -contains 'order=sequential' -and $manifest.Arguments -contains '-repeat' -and $manifest.Arguments -contains 'elo1=20') 'sequential, repeat and [0,20] reach Cute Chess'
    Assert-Check (-not (Test-Path -LiteralPath $parameters.LogPath) -and -not (Test-Path -LiteralPath $parameters.PgnPath)) 'preparation creates no match output'
    $oldIndex = [array]::IndexOf($manifest.Arguments, 'name=OLD')
    Assert-Check (-not (($manifest.Arguments[$oldIndex..($manifest.Arguments.Count - 1)] | Select-Object -First 20) -contains 'option.EvalType=Classical')) 'EvalType is not sent to the OLD engine'
    $changed = $manifest.PSObject.Copy(); $changed.Arguments[0] = '-wrong'
    Assert-Rejected { Assert-ReleaseSprtArguments $changed $parameters (Join-Path $env:JAVA 'bin\java.exe') $env:CUTECHESS } 'reject changed argument list'
    # Backward compatibility: the shared runner still defaults to random order for every older caller.
    $legacy = $parameters.Clone(); $legacy.Remove('OpeningOrder'); $legacyPath = Join-Path $temp 'legacy.json'
    & (Join-Path $PSScriptRoot 'sprt.ps1') @legacy -PrepareOnly -ArgumentManifestPath $legacyPath | Out-Null
    Assert-Check ((Get-Content -LiteralPath $legacyPath -Raw | ConvertFrom-Json).Arguments -contains 'order=random') 'sprt.ps1 default opening order unchanged'

    # Host qualification.
    $facts = @{ Platform = 'Win32NT'; ComputerName = 'RENEGADE'; CpuName = 'AMD Ryzen 7 7700X 8-Core Processor'
        PowerPlanRaw = '381b4222-f694-41f0-9685-ff5bb260df2e'; AffinityMask = 65535; FullAffinityMask = 65535
        JavaVersion = '21.0.10'; JavaRuntime = '21.0.10+7-LTS'; JavaVendor = 'Azul Systems, Inc.'; CutechessVersion = '1.5.1'; IdleCpuMean = 1.5 }
    Assert-ReleaseSprtHostFacts $facts
    foreach ($key in @('Platform', 'ComputerName', 'CpuName', 'PowerPlanRaw', 'JavaVersion', 'JavaRuntime', 'JavaVendor', 'CutechessVersion')) { $bad = $facts.Clone(); $bad[$key] = 'wrong'; Assert-Rejected { Assert-ReleaseSprtHostFacts $bad } "reject incorrect host/tool $key" }
    $bad = $facts.Clone(); $bad.AffinityMask = 1; Assert-Rejected { Assert-ReleaseSprtHostFacts $bad } 'reject restricted affinity'
    $bad = $facts.Clone(); $bad.IdleCpuMean = 5.5; Assert-Rejected { Assert-ReleaseSprtHostFacts $bad } 'reject a busy host'

    # UCI handshake contract.
    $baseOpts = Get-ReleaseSprtOptionNames $config.OldOptions
    $uciOld = "id name Vex`noption name Hash type spin default 64`noption name Threads type spin default 1`noption name PawnHashSize type spin default 1`noption name MultiPV type spin default 1`noption name Contempt type spin default 0`noption name OwnBook type check default false`noption name SyzygyOnline type check default false`noption name SyzygyPath type string default <empty>`nuciok`nreadyok`n"
    $null = Assert-ReleaseSprtHandshake $uciOld (@('Threads') + $baseOpts) @('EvalType', 'EvalFile')
    $script:checks++
    Assert-Rejected { Assert-ReleaseSprtHandshake $uciOld.Replace("readyok`n", '') $baseOpts @() } 'reject missing readyok'
    Assert-Rejected { Assert-ReleaseSprtHandshake $uciOld.Replace('option name Contempt type spin default 0', '') $baseOpts @() } 'reject a missing required option'
    Assert-Rejected { Assert-ReleaseSprtHandshake ($uciOld + "option name EvalType type combo`n") $baseOpts @('EvalType') } 'reject EvalType on the v0.5.7 side'
    Assert-Rejected { Assert-ReleaseSprtHandshake $uciOld.Replace('id name Vex', 'id name Other') $baseOpts @() } 'reject a different engine'

    # Fault detection and result classification (shared Phase 21 parser).
    foreach ($line in @('White loses on time', 'engine crashed', 'Warning: NEW disconnected', 'illegal move', "Warning: OLD doesn't have option EvalType", 'Engine OLD does not have an option named EvalType', 'Unknown option Hash')) {
        Assert-Check (Test-ReleaseSprtFaultLine $line) "fault line recognized: $line"
    }
    Assert-Check (-not (Test-ReleaseSprtFaultLine 'Finished game 12 (NEW vs OLD): 1/2-1/2 {Draw by adjudication}')) 'ordinary game line is not a fault'
    $pgn = (New-FixtureGame 'NEW' '1-0') + (New-FixtureGame 'OLD' '0-1')
    $log = "Finished game 1 (NEW vs OLD): 1-0 {White wins by adjudication}`nFinished game 2 (OLD vs NEW): 0-1 {Black wins by adjudication}`nScore of NEW vs OLD: 2 - 0 - 0  [1.000] 2`nSPRT: llr 3.1 (105.4%), lbound -2.94, ubound 2.94 - H1 was accepted`nFinished match`n"
    $r = Read-Stage6Results $log $pgn 0
    Assert-Check ($r.Verdict -eq 'H1_ACCEPTED' -and $r.CompletedOpeningPairs -eq 1 -and $r.CandidateByColor.White.Wins -eq 1 -and $r.CandidateByColor.Black.Wins -eq 1) 'H1 with a color-reversed pair'
    Assert-Check ((Read-Stage6Results ($log + 'engine crashed') $pgn 0).Verdict -eq 'SETUP_PROTOCOL_FAULT') 'a fault overrides H1'
    Assert-Check ((Read-Stage6Results $log $pgn 0 $true).Verdict -eq 'INTERRUPTED') 'an interrupted attempt is never accepted'
    Assert-Check ((Get-Stage6Verdict '' 20000 $true $false 0 $false) -eq 'INCONCLUSIVE') 'game cap without a boundary is inconclusive'

    # Provenance contract in the wrapper source, and its syntax.
    $wrapperPath = Join-Path $PSScriptRoot 'release-strength-sprt.ps1'
    $parseErrors = $null; $tokens = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($wrapperPath, [ref]$tokens, [ref]$parseErrors)
    Assert-Check ($parseErrors.Count -eq 0) 'wrapper syntax'
    $source = Get-Content -LiteralPath $wrapperPath -Raw
    Assert-Check ($source.IndexOf('attempt-start.json') -ge 0 -and $source.IndexOf('attempt-start.json') -lt $source.IndexOf('Start-Transcript') -and $source.IndexOf('Start-Transcript') -lt $source.IndexOf('Get-Counter')) 'attempt record is written before any probe'
    Assert-Check ($source -notmatch 'Remove-Item|git(\.exe)?[^\r\n]*(clean|reset|checkout)') 'wrapper never deletes or resets anything'
    Assert-Check ($source.Contains('Assert-ReleaseSprtHandshake') -and $source.Contains('-PrepareOnly') -and $source.Contains('Assert-ReleaseSprtArguments')) 'preflight handshakes both JARs and prepares the exact arguments'
    Write-Host "Release SPRT synthetic validation PASS: $script:checks checks. No engines, Cute Chess or games launched."
}
finally {
    $env:JAVA = $oldJava; $env:CUTECHESS = $oldCute
    Remove-Item -LiteralPath $temp -Recurse -Force
}
