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
        JavaVersion = '21.0.10'; JavaRuntime = '21.0.10+7-LTS'; JavaVendor = 'Azul Systems, Inc.'; CutechessVersion = '1.5.1'; HardwareLogicalProcessors = 16; IdleCpuMean = 1.5 }
    Assert-ReleaseSprtHostFacts $facts
    foreach ($key in @('Platform', 'ComputerName', 'CpuName', 'PowerPlanRaw', 'JavaVersion', 'JavaRuntime', 'JavaVendor', 'CutechessVersion')) { $bad = $facts.Clone(); $bad[$key] = 'wrong'; Assert-Rejected { Assert-ReleaseSprtHostFacts $bad } "reject incorrect host/tool $key" }
    $bad = $facts.Clone(); $bad.AffinityMask = 1; Assert-Rejected { Assert-ReleaseSprtHostFacts $bad } 'reject restricted affinity'
    $bad = $facts.Clone(); $bad.HardwareLogicalProcessors = 8; Assert-Rejected { Assert-ReleaseSprtHostFacts $bad } 'reject SMT off or a different processor count'
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

    # Smoke test: separate list, separate parameters, bounded size.
    $smokePath = Join-Path $repoRoot $config.SmokeOpeningRelativePath
    Assert-Check ((Get-Stage6Hash $smokePath) -ceq $config.SmokeOpeningSha256 -and $config.SmokeGames -eq 20) 'committed smoke list hash and size'
    Assert-ReleaseSprtOpenings $smokePath 10; $script:checks++
    $prodKeys = @(([IO.File]::ReadAllText($openingPath).TrimEnd("`n") -split "`n") | ForEach-Object { ($_ -split ' ')[0..3] -join ' ' })
    $smokeKeys = @(([IO.File]::ReadAllText($smokePath).TrimEnd("`n") -split "`n") | ForEach-Object { ($_ -split ' ')[0..3] -join ' ' })
    Assert-Check (@($smokeKeys | Where-Object { $prodKeys -contains $_ }).Count -eq 0) 'smoke and production lists share no position'
    Assert-Rejected { $l = @($good.TrimEnd("`n") -split "`n"); $l[1] = ($l[0] -split ' ')[0..3] -join ' '; $l[1] += ' 0 9'; [IO.File]::WriteAllText((Join-Path $temp 'counter.epd'), ($l -join "`n") + "`n"); Assert-ReleaseSprtOpenings (Join-Path $temp 'counter.epd') } 'reject positions that differ only in move counters'
    $smokeParameters = Get-ReleaseSprtParameters $temp (Join-Path $temp 'openings.epd') -Smoke
    Assert-Check ($smokeParameters.MaxGames -eq 20 -and $parameters.MaxGames -eq 20000) 'smoke is 20 games, production 20000'
    Assert-ReleaseSprtParameters $smokeParameters -Smoke
    Assert-Rejected { Assert-ReleaseSprtParameters $smokeParameters } 'smoke parameters are not accepted as production'
    Assert-Rejected { Assert-ReleaseSprtParameters $parameters -Smoke } 'production parameters are not accepted as smoke'
    $smokeManifestPath = Join-Path $temp 'smoke-prepared.json'
    & (Join-Path $PSScriptRoot 'sprt.ps1') @smokeParameters -PrepareOnly -ArgumentManifestPath $smokeManifestPath | Out-Null
    $smokeManifest = Get-Content -LiteralPath $smokeManifestPath -Raw | ConvertFrom-Json
    Assert-ReleaseSprtArguments $smokeManifest $smokeParameters (Join-Path $env:JAVA 'bin\java.exe') $env:CUTECHESS -Smoke
    Assert-Check ($smokeManifest.Arguments[($smokeManifest.Arguments.IndexOf('-games') + 1)] -eq '20' -and $smokeManifest.Arguments -contains '-repeat' -and $smokeManifest.Arguments -contains 'order=sequential') 'smoke is 20 color-reversed games in file order'
    Assert-Rejected { Assert-ReleaseSprtArguments $smokeManifest $smokeParameters (Join-Path $env:JAVA 'bin\java.exe') $env:CUTECHESS } 'smoke argument list is not accepted as production'

    # The game cap is on game starts. Hitting it is INCONCLUSIVE and never a hypothesis decision.
    function New-Result($Verdict, $Scored, $Cancelled, $Decision = '', $Faults = @(), $Issues = @(), $Finished = $true, $Exit = 0, $Pairs = 5, $Unpaired = 0) {
        [pscustomobject]@{ Verdict = $Verdict; Scored = $Scored; Cancelled = $Cancelled; Decision = $Decision; FaultLines = $Faults; AuditIssues = $Issues
            FinishedMatch = $Finished; ExitCode = $Exit; CompletedOpeningPairs = $Pairs; UnpairedScoredGames = $Unpaired }
    }
    Assert-Check ((Get-ReleaseSprtVerdict (New-Result 'INCOMPLETE' 19998 2)) -eq 'INCONCLUSIVE') 'cap reached through cancelled starts is INCONCLUSIVE'
    Assert-Check ((Get-ReleaseSprtVerdict (New-Result 'INCONCLUSIVE' 20000 0)) -eq 'INCONCLUSIVE') 'cap with all games scored stays INCONCLUSIVE'
    Assert-Check ((Get-ReleaseSprtVerdict (New-Result 'INCOMPLETE' 19998 1)) -eq 'INCOMPLETE') 'short of the cap is not INCONCLUSIVE'
    Assert-Check ((Get-ReleaseSprtVerdict (New-Result 'INCOMPLETE' 19998 2 '' @('engine crashed'))) -eq 'INCOMPLETE') 'a fault blocks INCONCLUSIVE'
    Assert-Check ((Get-ReleaseSprtVerdict (New-Result 'H1_ACCEPTED' 19990 2 'H1')) -eq 'H1_ACCEPTED') 'a Cute Chess decision is never overridden by the cap rule'
    foreach ($v in @((New-Result 'INCOMPLETE' 19998 2), (New-Result 'INCONCLUSIVE' 20000 0), (New-Result 'INTERRUPTED' 100 0))) {
        Assert-Check ((Get-ReleaseSprtVerdict $v) -notin @('H0_ACCEPTED', 'H1_ACCEPTED')) 'the cap or an interruption never becomes H0 or H1'
    }
    $capPgn = (New-FixtureGame 'NEW' '1/2-1/2') + (New-FixtureGame 'OLD' '1/2-1/2')
    $capLog = "Finished game 1 (NEW vs OLD): 1/2-1/2 {Draw by adjudication}`nFinished game 2 (OLD vs NEW): 1/2-1/2 {Draw by adjudication}`nScore of NEW vs OLD: 0 - 0 - 2  [0.500] 2`nSPRT: llr -0.1 (-3.4%), lbound -2.94, ubound 2.94`nFinished match`n"
    $capResult = Read-Stage6Results $capLog $capPgn 0
    Assert-Check ($capResult.Decision -eq '' -and $capResult.Verdict -notin @('H0_ACCEPTED', 'H1_ACCEPTED')) 'no decision line in the Cute Chess log means no hypothesis decision'

    # Smoke verdicts.
    Assert-Check ((Get-ReleaseSprtSmokeVerdict (New-Result 'H0_ACCEPTED' 20 0 'H0')) -eq 'SMOKE_PASSED') 'clean smoke passes whatever the SPRT label says'
    Assert-Check ((Get-ReleaseSprtSmokeVerdict (New-Result 'INCOMPLETE' 18 2 '' @() @() $true 0 9 0)) -eq 'SMOKE_PASSED') 'smoke may end with cancelled games'
    foreach ($bad in @((New-Result 'SETUP_PROTOCOL_FAULT' 20 0 '' @('White loses on time')), (New-Result 'INCOMPLETE' 0 0), (New-Result 'INCOMPLETE' 20 0 '' @() @() $false),
                       (New-Result 'INCOMPLETE' 20 0 '' @() @('x')), (New-Result 'INCOMPLETE' 20 0 '' @() @() $true 1), (New-Result 'INCOMPLETE' 19 0 '' @() @() $true 0 9 1), (New-Result 'INCOMPLETE' 1 0 '' @() @() $true 0 0 1))) {
        Assert-Check ((Get-ReleaseSprtSmokeVerdict $bad) -eq 'SMOKE_FAILED') 'smoke fails on fault, no games, unfinished match, audit issue, exit code, unpaired games or no pair'
    }

    # The smoke test requires an intact passed preflight for this exact commit and scripts.
    $resultsRoot = Join-Path $temp 'results'
    $scriptHashes = [ordered]@{ 'a.ps1' = 'aaa'; 'b.ps1' = 'bbb' }
    $fixtureNow = [datetime]::new(2026, 10, 12, 12, 0, 0, [DateTimeKind]::Utc)
    function New-FixturePreflight([string]$Name, [string]$Verdict = 'PREFLIGHT_PASSED', [string]$Head = 'head1', $Hashes = $scriptHashes, [switch]$NoSums, [datetime]$StartedUtc = [datetime]::new(2026, 10, 12, 11, 0, 0, [DateTimeKind]::Utc)) {
        $dir = Join-Path $resultsRoot $Name; New-Item -ItemType Directory -Path $dir -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $dir 'evidence.txt') -Value 'evidence'
        Save-Stage6Json @{ Verdict = $Verdict } (Join-Path $dir 'summary.json')
        Save-Stage6Json @{ InvokingHead = $Head; ScriptSha256 = $Hashes } (Join-Path $dir 'identity.json')
        Save-Stage6Json @{ StartedUtc = $StartedUtc.ToString('o') } (Join-Path $dir 'attempt-start.json')
        if (-not $NoSums) { Get-ChildItem -LiteralPath $dir -File | Where-Object { $_.Name -ne 'SHA256SUMS' } | ForEach-Object { '{0}  {1}' -f (Get-Stage6Hash $_.FullName), $_.Name } | Set-Content -LiteralPath (Join-Path $dir 'SHA256SUMS') -Encoding ASCII }
        return $dir
    }
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight (Join-Path $temp 'no-such-root') 'head1' $scriptHashes $fixtureNow)) 'no results directory means no qualifying preflight'
    $null = New-FixturePreflight 'preflight-20261012T000001000Z-fail' 'PREFLIGHT_FAILED'
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $scriptHashes $fixtureNow)) 'a failed preflight does not qualify'
    $null = New-FixturePreflight 'preflight-20261012T000002000Z-nosums' 'PREFLIGHT_PASSED' 'head1' $scriptHashes -NoSums
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $scriptHashes $fixtureNow)) 'a preflight without SHA256SUMS does not qualify'
    $emptyDir = New-FixturePreflight 'preflight-20261012T000002500Z-empty'
    Set-Content -LiteralPath (Join-Path $emptyDir 'SHA256SUMS') -Value '' -Encoding ASCII; Set-Content -LiteralPath (Join-Path $emptyDir 'evidence.txt') -Value 'tampered'
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $scriptHashes $fixtureNow)) 'an empty SHA256SUMS does not hide tampered evidence'
    $extraDir = New-FixturePreflight 'preflight-20261012T000002600Z-extra'
    Set-Content -LiteralPath (Join-Path $extraDir 'added-later.txt') -Value 'not in the manifest'
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $scriptHashes $fixtureNow)) 'a file missing from SHA256SUMS does not qualify'
    $null = New-FixturePreflight 'preflight-20261011T000000000Z-old' 'PREFLIGHT_PASSED' 'head1' $scriptHashes -StartedUtc ([datetime]::new(2026, 10, 10, 9, 0, 0, [DateTimeKind]::Utc))
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $scriptHashes $fixtureNow)) 'a preflight older than the age limit does not qualify'
    $good1 = New-FixturePreflight 'preflight-20261012T000003000Z-good'
    Assert-Check ((Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $scriptHashes $fixtureNow) -ceq 'preflight-20261012T000003000Z-good') 'an intact recent passed preflight qualifies'
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head2' $scriptHashes $fixtureNow)) 'a preflight from another commit does not qualify'
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' ([ordered]@{ 'a.ps1' = 'aaa'; 'b.ps1' = 'CHANGED' }) $fixtureNow)) 'a preflight from different scripts does not qualify'
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $scriptHashes ($fixtureNow.AddHours(-3)))) 'a preflight that starts after the check time does not qualify'
    Set-Content -LiteralPath (Join-Path $good1 'evidence.txt') -Value 'tampered'
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $scriptHashes $fixtureNow)) 'a preflight whose evidence no longer matches its checksums does not qualify'

    # Provenance contract in the wrapper source, and its syntax.
    $wrapperPath = Join-Path $PSScriptRoot 'release-strength-sprt.ps1'
    $parseErrors = $null; $tokens = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($wrapperPath, [ref]$tokens, [ref]$parseErrors)
    Assert-Check ($parseErrors.Count -eq 0) 'wrapper syntax'
    $source = Get-Content -LiteralPath $wrapperPath -Raw
    $at = $source.IndexOf('attempt-start.json')
    Assert-Check ($at -ge 0 -and $at -lt $source.IndexOf('Start-Transcript') -and $at -lt $source.IndexOf('WSL_INTEROP') -and $at -lt $source.IndexOf('Dirty checkout') -and $at -lt $source.IndexOf('Threading.Mutex') -and $source.IndexOf('Start-Transcript') -lt $source.IndexOf('Get-Counter')) 'attempt record is written before any platform, dirty-tree, mutex or host probe'
    Assert-Check ($source.Contains('Global\VexReleaseSprt') -and $source.Contains('ReleaseMutex')) 'single-instance mutex is taken and released'
    Assert-Check ($source.IndexOf('Script changed after the identity record') -gt $source.IndexOf('RUN-RELEASE-SPRT') -and $source.IndexOf('Script changed after the identity record') -lt $source.IndexOf('Start-Process')) 'script hashes are rechecked after the confirmation and before launch'
    Assert-Check ($source -notmatch 'Remove-Item|git(\.exe)?[^\r\n]*(clean|reset|checkout)') 'wrapper never deletes or resets anything'
    Assert-Check ($source.Contains('Assert-ReleaseSprtHandshake') -and $source.Contains('-PrepareOnly') -and $source.Contains('Assert-ReleaseSprtArguments')) 'preflight handshakes both JARs and prepares the exact arguments'
    Assert-Check ($source.Contains('RUN-RELEASE-SMOKE') -and $source.Contains('-SmokeNative') -and $source.IndexOf('Find-ReleaseSprtPassedPreflight') -lt $source.IndexOf('Start-Process')) 'smoke has its own confirmation phrase and checks the preflight before launching'
    Assert-Check ($source -notmatch '\$PSCommandPath|&\s+\$MyInvocation|Invoke-Expression|-ExecuteNative\s+-') 'no mode can start another mode'
    Assert-Check ($source.Contains('$config.SmokeOpeningRelativePath') -and $source.Contains('$config.OpeningRelativePath') -and ([regex]::Matches($source, 'Join-Path \$repo ''[^'']*openings')).Count -eq 0) 'opening lists are selected through the config only'
    Write-Host "Release SPRT synthetic validation PASS: $script:checks checks. No engines, Cute Chess or games launched."
}
finally {
    $env:JAVA = $oldJava; $env:CUTECHESS = $oldCute
    Remove-Item -LiteralPath $temp -Recurse -Force
}
