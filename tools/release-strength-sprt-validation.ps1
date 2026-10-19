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
    # NaN compares false with everything and $null -ge 0 is true, so a plain 'greater than the limit' test let both through.
    foreach ($value in @([double]::NaN, [double]::PositiveInfinity, [double]::NegativeInfinity, $null, '2.5', -1, 5.01)) {
        $bad = $facts.Clone(); $bad.IdleCpuMean = $value; Assert-Rejected { Assert-ReleaseSprtHostFacts $bad } "reject idle CPU reading '$value'"
    }
    foreach ($value in @(0, 5, 0.0, 4.99)) { $ok = $facts.Clone(); $ok.IdleCpuMean = $value; Assert-ReleaseSprtHostFacts $ok; $script:checks++ }

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

    # AssetDirectory: PowerShell and .NET disagree about a relative path when the session's location and the process directory differ.
    $psDirectory = Join-Path $temp 'psdir'; $netDirectory = Join-Path $temp 'netdir'; $assetsDirectory = Join-Path $psDirectory 'assets'
    New-Item -ItemType Directory -Path $assetsDirectory, $netDirectory | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $assetsDirectory 'asset.bin'), [byte[]](1, 2, 3))
    $savedLocation = (Get-Location).Path; $savedDirectory = [Environment]::CurrentDirectory
    try {
        Set-Location -LiteralPath $psDirectory; [Environment]::CurrentDirectory = $netDirectory
        Assert-Check ($null -ne (Get-FileHash -LiteralPath (Join-Path 'assets' 'asset.bin'))) 'PowerShell resolves a relative path against its own location'
        Assert-Rejected { [IO.File]::OpenRead((Join-Path 'assets' 'asset.bin')).Dispose() } 'a .NET API resolves the same relative path against the process directory and misses it (the defect)'
        $resolved = Resolve-ReleaseSprtDirectory 'assets' (Get-Location).ProviderPath
        Assert-Check ([IO.Path]::IsPathRooted($resolved) -and $resolved -match '^[A-Za-z]:\\') 'a relative asset directory resolves to a full drive path'
        [IO.File]::OpenRead((Join-Path $resolved 'asset.bin')).Dispose(); $script:checks++
        Assert-Check ($null -ne (Get-FileHash -LiteralPath (Join-Path $resolved 'asset.bin'))) 'the resolved path works for both PowerShell and .NET'
        Assert-Check ((Resolve-ReleaseSprtDirectory $assetsDirectory (Get-Location).ProviderPath) -ceq $resolved) 'an absolute path resolves to the same directory as the relative one'
        Assert-Check ((Resolve-ReleaseSprtDirectory '..\psdir\assets' (Get-Location).ProviderPath) -ceq $resolved) 'dot-dot segments are collapsed'
        Assert-Check ((Resolve-ReleaseSprtDirectory ($assetsDirectory + '\') (Get-Location).ProviderPath).TrimEnd('\') -ceq $resolved.TrimEnd('\')) 'a trailing separator does not change the directory'
        Set-Location -LiteralPath $netDirectory
        Assert-Check ((Resolve-ReleaseSprtDirectory $assetsDirectory (Get-Location).ProviderPath) -ceq $resolved) 'an absolute path does not depend on the current location'
        foreach ($bad in @('', '   ', '\\server\share\assets', '\assets', 'C:assets', 'no-such-directory', '..\psdir\assets\asset.bin')) {
            Assert-Rejected { Resolve-ReleaseSprtDirectory $bad (Get-Location).ProviderPath } "reject asset directory '$bad'"
        }
        Assert-Rejected { Resolve-ReleaseSprtDirectory 'assets' 'relative-base' } 'reject a relative base directory'
    }
    finally { Set-Location -LiteralPath $savedLocation; [Environment]::CurrentDirectory = $savedDirectory }

    # Machine-wide lock, with real second processes and a private lock name (never the production one). No engine or game is involved.
    $lockName = 'Global\VexReleaseSprtValidation-' + [guid]::NewGuid().ToString('N')
    $powerShellPath = (Get-Process -Id $PID).Path
    $readyFile = Join-Path $temp 'lock-holder-ready'
    function Start-LockChild([string]$Command) {
        $psi = New-Object Diagnostics.ProcessStartInfo
        $psi.FileName = $powerShellPath; $psi.Arguments = '-NoProfile -NonInteractive -Command "' + $Command + '"'
        $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
        return [Diagnostics.Process]::Start($psi)
    }
    # 0: another process could take the lock (so nobody holds it), 1: it was held, 2: it saw an abandoned lock.
    function Get-LockProbe([string]$Name = $lockName) {
        $child = Start-LockChild ("`$m = New-Object Threading.Mutex(`$false, '$Name'); try { if (`$m.WaitOne(0)) { `$m.ReleaseMutex(); exit 0 } else { exit 1 } } catch { exit 2 }")
        if (-not $child.WaitForExit(60000)) { $child.Kill(); throw 'Lock probe timed out.' }
        return $child.ExitCode
    }
    $holder = $null
    try {
        Assert-Check ((Get-LockProbe) -eq 0) 'the private lock starts free'
        $holder = Start-LockChild ("`$m = New-Object Threading.Mutex(`$false, '$lockName'); if (-not `$m.WaitOne(0)) { exit 3 }; Set-Content -LiteralPath '$readyFile' -Value held; Start-Sleep -Seconds 300")
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        while (-not (Test-Path -LiteralPath $readyFile) -and [DateTime]::UtcNow -lt $deadline -and -not $holder.HasExited) { Start-Sleep -Milliseconds 100 }
        Assert-Check (Test-Path -LiteralPath $readyFile) 'another process took the lock'
        Assert-Check ((Get-LockProbe) -eq 1) 'a third process sees the lock as held'
        $refusal = ''
        try { $null = Enter-ReleaseSprtLock $lockName '' } catch { $refusal = $_.ToString() }
        Assert-Check ($refusal -match 'Another release SPRT attempt is running') 'a second attempt is refused while the lock is held'
        $holder.Kill(); $null = $holder.WaitForExit(30000)
        $recovered = Enter-ReleaseSprtLock $lockName ''
        # .NET 10 hands a mutex left by a killed process to the next waiter without raising AbandonedMutexException, so Recovered is not asserted here.
        Assert-Check ($null -ne $recovered.Mutex) 'a lock held by a killed process is acquired by the next attempt: a dead attempt never blocks the next'
        Assert-Check ((Get-LockProbe) -eq 1) 'the recovering attempt now holds the lock'
        Exit-ReleaseSprtLock $recovered
        Assert-Check ((Get-LockProbe) -eq 0) 'releasing the recovered lock frees it for the next process'
        $normal = Enter-ReleaseSprtLock $lockName ''
        Assert-Check ($normal.Recovered -eq $false) 'an ordinary acquisition is not reported as recovered'
        Exit-ReleaseSprtLock $normal
        Assert-Check ((Get-LockProbe) -eq 0) 'the lock is free after an ordinary release'
        Exit-ReleaseSprtLock $null; $script:checks++
    }
    finally { if ($null -ne $holder -and -not $holder.HasExited) { $holder.Kill() } }
    # Where the runtime does signal abandonment (a thread that exits holding the lock), the attempt takes the lock over and says so.
    Add-Type -TypeDefinition 'using System.Threading; public static class ReleaseSprtLockAbandoner { public static void Hold(string name) { var t = new Thread(() => { var m = new Mutex(false, name); m.WaitOne(); }); t.Start(); t.Join(); } }'
    $threadLockName = 'Global\VexReleaseSprtValidation-' + [guid]::NewGuid().ToString('N')
    [ReleaseSprtLockAbandoner]::Hold($threadLockName)
    $takenOver = Enter-ReleaseSprtLock $threadLockName ''
    Assert-Check ($takenOver.Recovered -eq $true) 'a lock abandoned by an exited owner is taken over and reported as recovered'
    Exit-ReleaseSprtLock $takenOver
    $again = Enter-ReleaseSprtLock $threadLockName ''
    Assert-Check ($again.Recovered -eq $false) 'recovery is reported once: the next acquisition is ordinary'
    Exit-ReleaseSprtLock $again

    # The match runs in a child that outlives a wrapper killed outright. The child holds the match lock, so the next attempt must refuse to start.
    $matchName = 'Global\VexReleaseSprtValidationMatch-' + [guid]::NewGuid().ToString('N')
    $matchReady = Join-Path $temp 'match-holder-ready'
    $matchHolder = $null
    try {
        $matchHolder = Start-LockChild ("`$m = New-Object Threading.Mutex(`$false, '$matchName'); if (-not `$m.WaitOne(0)) { exit 3 }; Set-Content -LiteralPath '$matchReady' -Value held; Start-Sleep -Seconds 300")
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        while (-not (Test-Path -LiteralPath $matchReady) -and [DateTime]::UtcNow -lt $deadline -and -not $matchHolder.HasExited) { Start-Sleep -Milliseconds 100 }
        Assert-Check (Test-Path -LiteralPath $matchReady) 'a child process holds the match lock'
        $refusal = ''
        try { $null = Enter-ReleaseSprtLock $lockName $matchName } catch { $refusal = $_.ToString() }
        Assert-Check ($refusal -match 'still running') 'an attempt is refused while a match from an earlier attempt is still running'
        Assert-Check ((Get-LockProbe $lockName) -eq 0) 'the refused attempt released the main lock again'
        $matchHolder.Kill(); $null = $matchHolder.WaitForExit(30000)
        $afterKill = Enter-ReleaseSprtLock $lockName $matchName
        Assert-Check ($null -ne $afterKill.Mutex) 'once the match process is gone the next attempt can start'
        Exit-ReleaseSprtLock $afterKill
        Assert-Check ((Get-LockProbe $matchName) -eq 0 -and (Get-LockProbe $lockName) -eq 0) 'probing the match lock leaves neither lock held'
    }
    finally { if ($null -ne $matchHolder -and -not $matchHolder.HasExited) { $matchHolder.Kill() } }

    # The idle reading: only a full set of finite numbers counts.
    $steady = @(1..15 | ForEach-Object { [double]2.0 })
    $measured = Measure-ReleaseSprtIdle $steady
    Assert-Check ($measured.Mean -eq 2 -and $measured.Max -eq 2) 'a full steady idle sample is measured'
    Assert-Check ((Measure-ReleaseSprtIdle (@($steady[0..13]) + @([double]9.5))).Max -eq 9.5) 'the maximum of the sample is reported'
    foreach ($case in @(@('14 readings', @($steady[0..13])), @('empty', @()), @('NaN reading', (@($steady[0..13]) + @([double]::NaN))), @('infinite reading', (@($steady[0..13]) + @([double]::PositiveInfinity))),
                        @('null reading', (@($steady[0..13]) + @($null))), @('string reading', (@($steady[0..13]) + @('2.0'))))) {
        $bad = $case[1]; Assert-Rejected { Measure-ReleaseSprtIdle $bad } "reject an idle sample with $($case[0])"
    }

    # Gates. Fixture attempts use a config whose pinned hashes belong to small inert files, so every identity comparison is real.
    $inputs = Join-Path $temp 'gate-inputs'; New-Item -ItemType Directory -Path $inputs | Out-Null
    foreach ($name in @('candidate-v0.6.0.jar', 'baseline-v0.5.7.jar', 'openings-v1.epd', 'openings-smoke-v1.epd', 'preregistration.md')) { [IO.File]::WriteAllText((Join-Path $inputs $name), "fixture $name`n") }
    $fxConfig = Get-ReleaseSprtConfig
    $fxConfig.CandidateSha256 = Get-Stage6Hash (Join-Path $inputs 'candidate-v0.6.0.jar'); $fxConfig.BaselineSha256 = Get-Stage6Hash (Join-Path $inputs 'baseline-v0.5.7.jar')
    $fxConfig.OpeningSha256 = Get-Stage6Hash (Join-Path $inputs 'openings-v1.epd'); $fxConfig.SmokeOpeningSha256 = Get-Stage6Hash (Join-Path $inputs 'openings-smoke-v1.epd')
    $fxConfig.PreregistrationSha256 = Get-Stage6Hash (Join-Path $inputs 'preregistration.md')
    $fxScripts = [ordered]@{}; $counter = 0
    foreach ($name in Get-ReleaseSprtScriptNames) { $counter++; $fxScripts[$name] = ('{0:x}' -f $counter).PadLeft(64, 'a') }
    function Copy-FixtureScripts { $copy = [ordered]@{}; foreach ($key in $fxScripts.Keys) { $copy[$key] = $fxScripts[$key] }; return $copy }
    $resultsRoot = Join-Path $temp 'results'; New-Item -ItemType Directory -Path $resultsRoot | Out-Null
    $fixtureNow = [datetime]::new(2026, 10, 20, 12, 0, 0, [DateTimeKind]::Utc)
    function Write-FixtureSums([string]$Dir) {
        Get-ChildItem -LiteralPath $Dir -File | Where-Object { $_.Name -ne 'SHA256SUMS' } | Sort-Object Name |
            ForEach-Object { '{0}  {1}' -f (Get-Stage6Hash $_.FullName), $_.Name } | Set-Content -LiteralPath (Join-Path $Dir 'SHA256SUMS') -Encoding ASCII
    }
    # Change a JSON file the way a bug or a careless edit would, then regenerate the manifest so only the identity comparison can object.
    function Edit-FixtureJson([string]$Dir, [string]$File, [scriptblock]$Change) {
        $json = Get-Content -LiteralPath (Join-Path $Dir $File) -Raw | ConvertFrom-Json
        $null = & $Change $json
        Save-Stage6Json $json (Join-Path $Dir $File); Write-FixtureSums $Dir
    }
    function New-FixtureAttempt([string]$Name, [string]$Kind = 'preflight', $Started = $fixtureNow.AddHours(-2), $Ended = $null, [string]$Head = 'head1', $Hashes = $fxScripts, [string]$Verdict = 'PREFLIGHT_PASSED') {
        if ($null -eq $Ended) { $Ended = $Started.AddMinutes(1) }
        $smoke = $Kind -eq 'smoke'
        $dir = Join-Path $resultsRoot $Name; New-Item -ItemType Directory -Path $dir | Out-Null
        foreach ($file in @('candidate-v0.6.0.jar', 'baseline-v0.5.7.jar', 'preregistration.md', $(if ($smoke) { 'openings-smoke-v1.epd' } else { 'openings-v1.epd' }))) { Copy-Item -LiteralPath (Join-Path $inputs $file) -Destination $dir }
        Save-Stage6Json ([ordered]@{ RunId = $Name; Kind = $Kind; StartedUtc = $Started.ToString('o'); Status = 'STARTED'; InvokingHead = $Head; Branch = '' }) (Join-Path $dir 'attempt-start.json')
        Save-Stage6Json ([ordered]@{ RunId = $Name; Kind = $Kind; EndedUtc = $Ended.ToString('o'); MatchStarted = $smoke; MatchCompleted = $smoke; ExitCode = $(if ($smoke) { 0 } else { -1 }); Failure = '' }) (Join-Path $dir 'attempt-end.json')
        Save-Stage6Json ([ordered]@{ Verdict = $Verdict; Failure = ''; GamesStarted = 0 }) (Join-Path $dir 'summary.json')
        Save-Stage6Json ([ordered]@{ Kind = $Kind; InvokingHead = $Head; Config = $fxConfig; ScriptSha256 = $Hashes
            Assets = @([ordered]@{ Arm = 'candidate'; Sha256 = $fxConfig.CandidateSha256 }, [ordered]@{ Arm = 'baseline'; Sha256 = $fxConfig.BaselineSha256 }) }) (Join-Path $dir 'identity.json')
        Write-FixtureSums $dir
        return $dir
    }
    function Get-PreflightRefusal([string]$Name, [scriptblock]$Make) {
        $dir = @(& $Make $Name)[-1]
        $why = New-Object Collections.Generic.List[string]
        $found = Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $fxScripts $fixtureNow $fxConfig $why
        Remove-Item -LiteralPath $dir -Recurse -Force
        return [pscustomobject]@{ Found = $found; Why = ($why -join "`n") }
    }
    function Assert-PreflightRefused([string]$Because, [string]$Pattern, [scriptblock]$Make) {
        $r = Get-PreflightRefusal 'preflight-20261020T100000000Z-case' $Make
        Assert-Check ($null -eq $r.Found -and $r.Why -match $Pattern) "preflight refused: $Because"
    }
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight (Join-Path $temp 'no-such-root') 'head1' $fxScripts $fixtureNow $fxConfig)) 'no results directory means no qualifying preflight'
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $fxScripts $fixtureNow $fxConfig)) 'an empty results directory means no qualifying preflight'
    Assert-PreflightRefused 'failed verdict' 'PREFLIGHT_FAILED' { param($n) New-FixtureAttempt $n -Verdict 'PREFLIGHT_FAILED' }
    Assert-PreflightRefused 'no SHA256SUMS' 'SHA256SUMS is missing' { param($n) $d = New-FixtureAttempt $n; Remove-Item (Join-Path $d 'SHA256SUMS'); $d }
    Assert-PreflightRefused 'older runner record without attempt-end' 'attempt-end.json is missing|SHA256SUMS' { param($n) $d = New-FixtureAttempt $n; Remove-Item (Join-Path $d 'attempt-end.json'); Write-FixtureSums $d; $d }
    Assert-PreflightRefused 'empty SHA256SUMS hiding tampered evidence' 'SHA256SUMS is empty' { param($n) $d = New-FixtureAttempt $n; Set-Content -LiteralPath (Join-Path $d 'SHA256SUMS') -Value '' -Encoding ASCII; $d }
    Assert-PreflightRefused 'file missing from SHA256SUMS' 'file not in SHA256SUMS: added-later.txt' { param($n) $d = New-FixtureAttempt $n; Set-Content -LiteralPath (Join-Path $d 'added-later.txt') -Value 'x'; $d }
    Assert-PreflightRefused 'evidence changed after the manifest was written' 'hash mismatch: summary.json' { param($n) $d = New-FixtureAttempt $n; Set-Content -LiteralPath (Join-Path $d 'summary.json') -Value '{"Verdict":"PREFLIGHT_PASSED","GamesStarted":0} ' ; $d }
    Assert-PreflightRefused 'manifest names a path outside the directory' 'malformed SHA256SUMS line' { param($n) $d = New-FixtureAttempt $n; Add-Content -LiteralPath (Join-Path $d 'SHA256SUMS') -Value ('{0}  ..\summary.json' -f ('a' * 64)); $d }
    Assert-PreflightRefused 'subdirectory the manifest cannot cover' 'unverifiable subdirectory' { param($n) $d = New-FixtureAttempt $n; New-Item -ItemType Directory -Path (Join-Path $d 'extra') | Out-Null; $d }
    Assert-PreflightRefused 'started 24 h 1 min ago' 'stale' { param($n) New-FixtureAttempt $n -Started $fixtureNow.AddHours(-24).AddMinutes(-1) }
    Assert-PreflightRefused 'starts after the check time' 'future' { param($n) New-FixtureAttempt $n -Started $fixtureNow.AddMinutes(30) }
    Assert-PreflightRefused 'ended before it started' 'ended before it started' { param($n) New-FixtureAttempt $n -Ended $fixtureNow.AddHours(-3) }
    Assert-PreflightRefused 'start record from another attempt mixed in' 'different attempt than this directory' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'attempt-start.json' { param($j) $j.RunId = 'preflight-20261020T000000000Z-other' }; $d }
    Assert-PreflightRefused 'end record from another attempt mixed in' 'different attempt than this directory' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'attempt-end.json' { param($j) $j.RunId = 'preflight-20261020T000000000Z-other' }; $d }
    Assert-PreflightRefused 'start time has no time zone' 'no time zone' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'attempt-start.json' { param($j) $j.StartedUtc = '2026-10-20T10:00:00' }; $d }
    Assert-PreflightRefused 'end time is not ISO 8601' 'not ISO 8601' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'attempt-end.json' { param($j) $j.EndedUtc = 'Oct 20 2026 10:00' }; $d }
    $linkProbe = Join-Path $temp 'link-probe'
    $canLink = $true; try { $null = New-Item -ItemType SymbolicLink -Path $linkProbe -Target (Join-Path $inputs 'preregistration.md') -ErrorAction Stop } catch { $canLink = $false }
    if ($canLink) {
        Assert-PreflightRefused 'a listed file replaced by a link to a file outside the directory' 'link' { param($n) $d = New-FixtureAttempt $n; $outside = Join-Path $temp 'outside-summary.json'; Copy-Item (Join-Path $d 'summary.json') $outside; Remove-Item (Join-Path $d 'summary.json'); $null = New-Item -ItemType SymbolicLink -Path (Join-Path $d 'summary.json') -Target $outside; Write-FixtureSums $d; $d }
    }
    # A junction needs no privilege and carries the same reparse-point attribute as a symbolic link.
    Assert-PreflightRefused 'a junction inside the evidence directory' 'link in the evidence directory: pointing-out' { param($n) $d = New-FixtureAttempt $n; $null = New-Item -ItemType Junction -Path (Join-Path $d 'pointing-out') -Target $inputs; $d }
    Assert-PreflightRefused 'different commit' 'different commit' { param($n) New-FixtureAttempt $n -Head 'head2' }
    Assert-PreflightRefused 'different script hashes' 'script hashes differ: sprt.ps1' { param($n) $h = Copy-FixtureScripts; $h['sprt.ps1'] = 'f' * 64; New-FixtureAttempt $n -Hashes $h }
    Assert-PreflightRefused 'script hash not recorded (runner before the audit tool existed)' 'script hashes differ: release-sprt-audit.py' { param($n) $h = [ordered]@{}; foreach ($k in $fxScripts.Keys) { if ($k -ne 'release-sprt-audit.py') { $h[$k] = $fxScripts[$k] } }; New-FixtureAttempt $n -Hashes $h }
    Assert-PreflightRefused 'recorded candidate identity differs' 'CandidateSha256' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'identity.json' { param($j) $j.Config.CandidateSha256 = '0' * 64 }; $d }
    Assert-PreflightRefused 'recorded baseline asset hash differs' 'recorded JAR identities differ' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'identity.json' { param($j) $j.Assets[1].Sha256 = '0' * 64 }; $d }
    Assert-PreflightRefused 'recorded opening corpus hash differs' 'OpeningSha256' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'identity.json' { param($j) $j.Config.OpeningSha256 = '0' * 64 }; $d }
    Assert-PreflightRefused 'recorded preregistration hash differs' 'PreregistrationSha256' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'identity.json' { param($j) $j.Config.PreregistrationSha256 = '0' * 64 }; $d }
    Assert-PreflightRefused 'frozen SPRT bound changed' 'frozen configuration differs: Elo1' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'identity.json' { param($j) $j.Config.Elo1 = 25 }; $d }
    Assert-PreflightRefused 'frozen game cap changed' 'MaxGames' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'identity.json' { param($j) $j.Config.MaxGames = 30000 }; $d }
    Assert-PreflightRefused 'frozen engine option changed' 'NewOptions' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'identity.json' { param($j) $j.Config.NewOptions[0] = 'Hash=32' }; $d }
    Assert-PreflightRefused 'archived JAR replaced and manifest regenerated' 'archived candidate-v0.6.0.jar differs' { param($n) $d = New-FixtureAttempt $n; Set-Content -LiteralPath (Join-Path $d 'candidate-v0.6.0.jar') -Value 'other'; Write-FixtureSums $d; $d }
    Assert-PreflightRefused 'archived opening list replaced and manifest regenerated' 'archived openings-v1.epd differs' { param($n) $d = New-FixtureAttempt $n; Set-Content -LiteralPath (Join-Path $d 'openings-v1.epd') -Value 'other'; Write-FixtureSums $d; $d }
    Assert-PreflightRefused 'archived preregistration replaced and manifest regenerated' 'archived preregistration.md differs' { param($n) $d = New-FixtureAttempt $n; Set-Content -LiteralPath (Join-Path $d 'preregistration.md') -Value 'other'; Write-FixtureSums $d; $d }
    Assert-PreflightRefused 'archived JAR missing' 'archived baseline-v0.5.7.jar is missing' { param($n) $d = New-FixtureAttempt $n; Remove-Item (Join-Path $d 'baseline-v0.5.7.jar'); Write-FixtureSums $d; $d }
    Assert-PreflightRefused 'attempt-end records a failure' 'failure or a started match' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'attempt-end.json' { param($j) $j.Failure = 'boom' }; $d }
    Assert-PreflightRefused 'a match was started' 'failure or a started match' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'attempt-end.json' { param($j) $j.MatchStarted = $true }; $d }
    Assert-PreflightRefused 'games were started' 'not PREFLIGHT_PASSED with no games' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'summary.json' { param($j) $j.GamesStarted = 2 }; $d }
    Assert-PreflightRefused 'a smoke attempt is not a preflight' 'not a preflight attempt' { param($n) New-FixtureAttempt $n -Kind 'smoke' }
    Assert-PreflightRefused 'corrupted identity.json' 'could not be evaluated' { param($n) $d = New-FixtureAttempt $n; Set-Content -LiteralPath (Join-Path $d 'identity.json') -Value '{ not json'; Write-FixtureSums $d; $d }
    Assert-PreflightRefused 'corrupted timestamps' 'could not be evaluated' { param($n) $d = New-FixtureAttempt $n; Edit-FixtureJson $d 'attempt-start.json' { param($j) $j.StartedUtc = 'yesterday-ish' }; $d }
    $accepted = Get-PreflightRefusal 'preflight-20261020T100000000Z-case' { param($n) New-FixtureAttempt $n }
    Assert-Check ($accepted.Found -ceq 'preflight-20261020T100000000Z-case') 'an intact preflight from this commit, scripts and frozen experiment qualifies'
    $edge = Get-PreflightRefusal 'preflight-20261020T100000000Z-case' { param($n) New-FixtureAttempt $n -Started $fixtureNow.AddHours(-24).AddMinutes(1) }
    Assert-Check ($edge.Found -ceq 'preflight-20261020T100000000Z-case') 'a preflight that started 23 h 59 min ago still qualifies'
    $goodPreflight = New-FixtureAttempt 'preflight-20261020T090000000Z-good'
    $null = New-FixtureAttempt 'preflight-20261020T110000000Z-failed' -Verdict 'PREFLIGHT_FAILED'
    $null = New-FixtureAttempt 'preflight-20261020T111000000Z-other-commit' -Head 'head2'
    $why = New-Object Collections.Generic.List[string]
    Assert-Check ((Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $fxScripts $fixtureNow $fxConfig $why) -ceq 'preflight-20261020T090000000Z-good' -and $why.Count -eq 2) 'newer failed or foreign preflights are skipped and reported; the qualifying one is found'
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $fxScripts $fixtureNow.AddHours(23) $fxConfig)) 'the same preflight no longer qualifies once it is past the limit'
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $fxScripts $fixtureNow.AddHours(-3) $fxConfig)) 'a preflight that starts after the check time does not qualify'
    Set-Content -LiteralPath (Join-Path $goodPreflight 'summary.json') -Value 'tampered'
    Assert-Check ($null -eq (Find-ReleaseSprtPassedPreflight $resultsRoot 'head1' $fxScripts $fixtureNow $fxConfig)) 'a preflight whose evidence no longer matches its checksums does not qualify'
    Get-ChildItem -LiteralPath $resultsRoot -Directory | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force }

    # Production also needs an independently audited smoke attempt. SMOKE_PASSED from the runner alone must never be enough.
    $smokeName = 'smoke-20261020T100000000Z-aaaaaaaa'; $auditName = 'audit-20261020T101000000Z-bbbbbbbb'
    function New-FixtureAudit([string]$Name, [string]$SmokeName, [scriptblock]$Change = $null) {
        $smokeDirectory = Join-Path $resultsRoot $SmokeName
        $record = [ordered]@{ Schema = 1; Verdict = 'AUDIT_APPROVED'; AuditedUtc = $fixtureNow.AddHours(-1).ToString('o')
            AuditTool = [ordered]@{ Name = 'release-sprt-audit.py'; Sha256 = $fxScripts['release-sprt-audit.py']; AnalyzerSha256 = $fxScripts['release-sprt-analyze.py'] }
            Smoke = [ordered]@{ RunId = $SmokeName; ManifestSha256 = (Get-Stage6Hash (Join-Path $smokeDirectory 'SHA256SUMS')); InvokingHead = 'head1'; ScriptSha256 = $fxScripts
                StartedUtc = $fixtureNow.AddHours(-2).ToString('o'); EndedUtc = $fixtureNow.AddHours(-2).AddMinutes(1).ToString('o'); LocationVerified = $true }
            Counts = [ordered]@{}; Criteria = @(Get-ReleaseSprtAuditCriteria | ForEach-Object { [ordered]@{ Id = $_; Passed = $true; Detail = 'fixture' } }) }
        if ($Change) { $null = & $Change $record }
        $dir = Join-Path $resultsRoot $Name; New-Item -ItemType Directory -Path $dir | Out-Null
        Save-Stage6Json $record (Join-Path $dir 'smoke-audit.json'); Write-FixtureSums $dir
        return $dir
    }
    function Get-SmokeGateResult([scriptblock]$Before = $null, [scriptblock]$Change = $null, [scriptblock]$After = $null, $Hashes = $fxScripts, [string]$Head = 'head1', [switch]$NoAudit) {
        $smokeDirectory = New-FixtureAttempt $smokeName 'smoke'
        if ($Before) { $null = & $Before $smokeDirectory }
        $auditDirectory = if ($NoAudit) { $null } else { New-FixtureAudit $auditName $smokeName $Change }
        if ($After) { $null = & $After $smokeDirectory $auditDirectory }
        $why = New-Object Collections.Generic.List[string]
        $found = Find-ReleaseSprtApprovedSmoke $resultsRoot $Head $Hashes $fixtureNow $fxConfig $why
        Get-ChildItem -LiteralPath $resultsRoot -Directory | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force }
        return [pscustomobject]@{ Found = $found; Why = ($why -join "`n") }
    }
    function Assert-SmokeRefused([string]$Because, [string]$Pattern, [hashtable]$Arguments) {
        $r = Get-SmokeGateResult @Arguments
        Assert-Check ($null -eq $r.Found -and $r.Why -match $Pattern) "audited smoke refused: $Because"
    }
    $r = Get-SmokeGateResult
    Assert-Check ($r.Found.Audit -ceq $auditName -and $r.Found.Smoke -ceq $smokeName -and $r.Found.ManifestSha256 -match '^[0-9a-f]{64}$') 'an approved audit of an intact smoke attempt from this commit and scripts authorizes production'
    $r = Get-SmokeGateResult -NoAudit
    Assert-Check ($null -eq $r.Found) 'a clean smoke attempt with no audit record does not authorize production (SMOKE_PASSED alone is not enough)'
    Assert-SmokeRefused 'audit verdict is rejected' 'audit verdict is AUDIT_REJECTED' @{ Change = { param($r) $r.Verdict = 'AUDIT_REJECTED' } }
    Assert-SmokeRefused 'unknown schema' 'unknown audit schema' @{ Change = { param($r) $r.Schema = 2 } }
    Assert-SmokeRefused 'a criterion failed' 'criterion failed: opening-pairs' @{ Change = { param($r) $r.Criteria[7].Passed = $false } }
    Assert-SmokeRefused 'a criterion was not audited' 'criterion not audited: no-faults' @{ Change = { param($r) $r.Criteria = @($r.Criteria | Where-Object { $_.Id -ne 'no-faults' }) } }
    Assert-SmokeRefused 'an unexpected extra criterion' 'unexpected criteria' @{ Change = { param($r) $r.Criteria = @($r.Criteria) + [ordered]@{ Id = 'extra'; Passed = $true; Detail = '' } } }
    Assert-SmokeRefused 'a criterion marked passed with a string' 'criterion failed: games-complete' @{ Change = { param($r) $r.Criteria[6].Passed = 'yes' } }
    Assert-SmokeRefused 'audited by a different audit program' 'different audit program' @{ Change = { param($r) $r.AuditTool.Sha256 = 'e' * 64 } }
    Assert-SmokeRefused 'audit could not verify the location' 'could not confirm that the recorded paths' @{ Change = { param($r) $r.Smoke.LocationVerified = $false } }
    Assert-SmokeRefused 'audit has no location flag' 'could not confirm that the recorded paths' @{ Change = { param($r) $r.Smoke.Remove('LocationVerified') } }
    Assert-SmokeRefused 'location flag is a string' 'could not confirm that the recorded paths' @{ Change = { param($r) $r.Smoke.LocationVerified = 'True' } }
    Assert-SmokeRefused 'smoke ended before it started' 'ended before it started' @{ Before = { param($s) Edit-FixtureJson $s 'attempt-end.json' { param($j) $j.EndedUtc = $fixtureNow.AddHours(-5).ToString('o') } } }
    Assert-SmokeRefused 'audited with a different analyzer' 'different analyzer' @{ Change = { param($r) $r.AuditTool.AnalyzerSha256 = 'e' * 64 } }
    Assert-SmokeRefused 'audit record without an analyzer hash' 'different analyzer' @{ Change = { param($r) $r.AuditTool.Remove('AnalyzerSha256') } }
    Assert-SmokeRefused 'smoke attempt records from another attempt mixed in' 'different attempt than this directory' @{ Before = { param($s) Edit-FixtureJson $s 'attempt-end.json' { param($j) $j.RunId = 'smoke-20261020T000000000Z-cccccccc' } } }
    Assert-SmokeRefused 'audited smoke ran on another commit' 'different commit' @{ Change = { param($r) $r.Smoke.InvokingHead = 'head2' } }
    Assert-SmokeRefused 'audited smoke ran on other scripts' 'different scripts: sprt.ps1' @{ Change = { param($r) $h = Copy-FixtureScripts; $h['sprt.ps1'] = 'f' * 64; $r.Smoke.ScriptSha256 = $h } }
    Assert-SmokeRefused 'this checkout is on another commit' 'different commit' @{ Head = 'head2' }
    $changedScripts = Copy-FixtureScripts; $changedScripts['release-strength-sprt.ps1'] = 'd' * 64
    Assert-SmokeRefused 'this checkout has different scripts' 'different scripts: release-strength-sprt.ps1' @{ Hashes = $changedScripts }
    Assert-SmokeRefused 'manifest differs from the one audited' 'not the one that was audited' @{ Change = { param($r) $r.Smoke.ManifestSha256 = '0' * 64 } }
    Assert-SmokeRefused 'smoke evidence changed and re-manifested after the audit' 'not the one that was audited' @{ After = { param($s, $a) Set-Content -LiteralPath (Join-Path $s 'extra.txt') -Value 'late'; Write-FixtureSums $s } }
    Assert-SmokeRefused 'smoke file changed after the audit' 'hash mismatch: summary.json' @{ After = { param($s, $a) Set-Content -LiteralPath (Join-Path $s 'summary.json') -Value 'edited' } }
    Assert-SmokeRefused 'audit record changed after it was written' 'hash mismatch: smoke-audit.json' @{ After = { param($s, $a) $p = Join-Path $a 'smoke-audit.json'; Set-Content -LiteralPath $p -Value ((Get-Content -LiteralPath $p -Raw) + ' ') } }
    Assert-SmokeRefused 'audit record without a manifest' 'SHA256SUMS is missing' @{ After = { param($s, $a) Remove-Item (Join-Path $a 'SHA256SUMS') } }
    Assert-SmokeRefused 'audit record that is not JSON' 'could not be evaluated' @{ After = { param($s, $a) Set-Content -LiteralPath (Join-Path $a 'smoke-audit.json') -Value 'nope'; Write-FixtureSums $a } }
    Assert-SmokeRefused 'audited smoke attempt is gone' 'audited smoke attempt is missing' @{ After = { param($s, $a) Remove-Item -LiteralPath $s -Recurse -Force } }
    Assert-SmokeRefused 'run id that escapes the results directory' 'malformed smoke run id' @{ Change = { param($r) $r.Smoke.RunId = '..\preflight-x' } }
    Assert-SmokeRefused 'audited before the smoke ended' 'before the smoke ended' @{ Change = { param($r) $r.AuditedUtc = $fixtureNow.AddHours(-3).ToString('o') } }
    Assert-SmokeRefused 'smoke attempt did not exit cleanly' 'completed, clean match' @{ Before = { param($s) Edit-FixtureJson $s 'attempt-end.json' { param($j) $j.ExitCode = 1 } } }
    Assert-SmokeRefused 'smoke attempt reports a failure' 'completed, clean match' @{ Before = { param($s) Edit-FixtureJson $s 'attempt-end.json' { param($j) $j.Failure = 'x' } } }
    Assert-SmokeRefused 'smoke archived JAR differs' 'archived candidate-v0.6.0.jar differs' @{ Before = { param($s) Set-Content -LiteralPath (Join-Path $s 'candidate-v0.6.0.jar') -Value 'other'; Write-FixtureSums $s } }
    Assert-SmokeRefused 'smoke used a different opening list' 'archived openings-smoke-v1.epd differs' @{ Before = { param($s) Set-Content -LiteralPath (Join-Path $s 'openings-smoke-v1.epd') -Value 'other'; Write-FixtureSums $s } }
    Assert-SmokeRefused 'smoke frozen configuration differs' 'frozen configuration differs: TC' @{ Before = { param($s) Edit-FixtureJson $s 'identity.json' { param($j) $j.Config.TC = '10+0.1' } } }
    # A preflight directory cannot stand in for the smoke attempt.
    $null = New-FixtureAttempt 'preflight-20261020T100000000Z-pre'
    $null = New-FixtureAudit $auditName 'preflight-20261020T100000000Z-pre'
    $why = New-Object Collections.Generic.List[string]
    Assert-Check ($null -eq (Find-ReleaseSprtApprovedSmoke $resultsRoot 'head1' $fxScripts $fixtureNow $fxConfig $why) -and (($why -join '') -match 'malformed smoke run id')) 'an audit that names a preflight directory is refused'
    Get-ChildItem -LiteralPath $resultsRoot -Directory | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force }

    $listed = New-Object Collections.Generic.List[string]; 1..5 | ForEach-Object { $listed.Add("candidate-$_`: reason") }
    Assert-Check ((Format-ReleaseSprtRejections $null) -match 'No candidate attempt directory exists' -and (Format-ReleaseSprtRejections $listed) -match 'Rejected \(5\): candidate-1: reason \| candidate-2: reason \| candidate-3: reason$') 'refusal messages name the first three rejected candidates and the total'

    # The runner's list of hashed files and the criteria list match what the audit program is documented to check.
    foreach ($name in Get-ReleaseSprtScriptNames) { Assert-Check (Test-Path -LiteralPath (Join-Path $PSScriptRoot $name)) "hashed script exists: $name" }
    Assert-Check ((Get-ReleaseSprtAuditCriteria).Count -eq 12 -and ((Get-ReleaseSprtAuditCriteria | Select-Object -Unique).Count -eq 12)) 'twelve distinct audit criteria'

    # Provenance contract in the wrapper source, and its syntax.
    # Timestamps: zone-qualified forms are read as the same instant, zone-less ones are refused rather than read in local time.
    $instant = [datetime]::new(2026, 10, 20, 10, 0, 0, [DateTimeKind]::Utc)
    foreach ($text in @('2026-10-20T10:00:00Z', '2026-10-20T10:00:00.0000000Z', '2026-10-20T15:30:00+05:30', '2026-10-20T03:00:00-07:00')) {
        Assert-Check ((ConvertTo-ReleaseSprtUtc $text) -eq $instant) "timestamp $text is read as the same instant"
    }
    Assert-Check ((ConvertTo-ReleaseSprtUtc $instant) -eq $instant -and (ConvertTo-ReleaseSprtUtc $instant.ToLocalTime()) -eq $instant) 'DateTime values with a kind are read as the same instant'
    foreach ($text in @('2026-10-20T10:00:00', '2026-10-20 10:00:00Z', '10/20/2026 10:00:00', '', 'yesterday')) { Assert-Rejected { ConvertTo-ReleaseSprtUtc $text } "reject timestamp '$text'" }
    Assert-Rejected { ConvertTo-ReleaseSprtUtc ([datetime]::new(2026, 10, 20, 10, 0, 0, [DateTimeKind]::Unspecified)) } 'reject a DateTime with no kind'

    # The child process re-reads the parameter file after the confirmation. After a JSON round trip it must still equal the frozen experiment,
    # and an edit to it must be rejected by the frozen assertions even if every digest file were edited to match.
    foreach ($case in @(@($parameters, $false), @($smokeParameters, $true))) {
        $isSmoke = $case[1]; $roundTrip = Join-Path $temp 'roundtrip-parameters.json'
        Save-Stage6Json $case[0] $roundTrip
        $table = @{}; foreach ($property in ([Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($roundTrip)).TrimStart([char]0xFEFF) | ConvertFrom-Json).psobject.Properties) { $table[$property.Name] = $property.Value }
        Assert-ReleaseSprtParameters $table -Smoke:$isSmoke; $script:checks++
        foreach ($edit in @(@('Elo1', 25), @('MaxGames', 999999), @('TC', '10+0.1'), @('Concurrency', 8), @('OpeningOrder', 'random'))) {
            $changed = $table.Clone(); $changed[$edit[0]] = $edit[1]
            Assert-Rejected { Assert-ReleaseSprtParameters $changed -Smoke:$isSmoke } "a parameter file edited after confirmation is rejected: $($edit[0]) (smoke=$isSmoke)"
        }
    }

    $wrapperPath = Join-Path $PSScriptRoot 'release-strength-sprt.ps1'
    $parseErrors = $null; $tokens = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($wrapperPath, [ref]$tokens, [ref]$parseErrors)
    Assert-Check ($parseErrors.Count -eq 0) 'wrapper syntax'
    $source = Get-Content -LiteralPath $wrapperPath -Raw
    $commonSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release-strength-sprt-common.ps1') -Raw
    $at = $source.IndexOf('attempt-start.json')
    Assert-Check ($at -ge 0 -and $at -lt $source.IndexOf('Start-Transcript') -and $at -lt $source.IndexOf('WSL_INTEROP') -and $at -lt $source.IndexOf('Dirty checkout') -and $at -lt $source.IndexOf('Enter-ReleaseSprtLock') -and $source.IndexOf('Start-Transcript') -lt $source.IndexOf('Get-ReleaseSprtIdleReading')) 'attempt record is written before any platform, dirty-tree, lock or host probe'
    Assert-Check ($source.Contains('Enter-ReleaseSprtLock') -and $source.Contains('Exit-ReleaseSprtLock') -and $commonSource.Contains('[string]$Name = ''Global\VexReleaseSprt''') -and $commonSource.Contains('AbandonedMutexException')) 'single-instance machine-wide lock is taken, recovered when abandoned and released'
    Assert-Check ($source.IndexOf('Resolve-ReleaseSprtDirectory') -gt 0 -and $source.IndexOf('Resolve-ReleaseSprtDirectory') -lt $source.IndexOf('Join-Path $AssetDirectory') -and $source.IndexOf('Resolve-ReleaseSprtDirectory') -gt $source.IndexOf('try {')) 'the asset directory is resolved to a full path, inside the recorded attempt, before any JAR is opened'
    Assert-Check ($source.Contains('(Get-Location).ProviderPath')) 'a relative asset directory is resolved against the PowerShell location'
    $preflightGate = $source.IndexOf('Find-ReleaseSprtPassedPreflight'); $auditGate = $source.IndexOf('Find-ReleaseSprtApprovedSmoke')
    Assert-Check ($preflightGate -gt $source.IndexOf('try {') -and $auditGate -gt $preflightGate -and $auditGate -lt $source.IndexOf('Read-Host') -and $auditGate -lt $source.IndexOf('Start-Process') -and $source.IndexOf('finally {') -gt $auditGate) 'both gates run inside the recorded attempt, before the confirmation prompt and before any process is launched'
    $executeBlock = $source.IndexOf('if ($ExecuteNative) {', $preflightGate)
    Assert-Check ($source.Contains('if ($launchesGames) {') -and $source.IndexOf('if ($launchesGames) {') -lt $preflightGate -and $executeBlock -gt $preflightGate -and $executeBlock -lt $auditGate) 'the preflight gate covers smoke and production; the audit gate covers production only'
    Assert-Check ($source.IndexOf('attempt-end.json') -gt $source.IndexOf('finally {') -and $source.IndexOf('SHA256SUMS') -gt $source.IndexOf('attempt-end.json')) 'every outcome, refusals included, ends with attempt-end.json and SHA256SUMS'
    Assert-Check ($source.IndexOf('Script changed after the identity record') -gt $source.IndexOf('Read-Host') -and $source.IndexOf('Script changed after the identity record') -lt $source.IndexOf('Start-Process')) 'script hashes are rechecked after the confirmation and before launch'
    Assert-Check ($source -notmatch 'Remove-Item|git(\.exe)?[^\r\n]*(clean|reset|checkout)') 'wrapper never deletes or resets anything'
    Assert-Check ($source.Contains('Assert-ReleaseSprtHandshake') -and $source.Contains('-PrepareOnly') -and $source.Contains('Assert-ReleaseSprtArguments')) 'preflight handshakes both JARs and prepares the exact arguments'
    $childHash = $source.IndexOf('''Runner parameters changed before launch.'''); $childAssert = $source.IndexOf('Assert-ReleaseSprtParameters $argsTable'); $childRun = $source.IndexOf('& ''__RUNNER__''')
    Assert-Check ($childHash -gt 0 -and $childAssert -gt $childHash -and $childRun -gt $childAssert) 'the launched child checks the parameter file hash and the frozen parameters before it invokes the match runner'
    Assert-Check ($source.Contains('-cne $parametersSha') -and $source.Contains('-cne $preparedSha') -and $source -notmatch 'Get-Content[^\r\n]*identity\.json') 'launch digests are held in memory and in the child command line, not re-read from identity.json'
    $childScripts = $source.IndexOf('Script changed before the match runner started'); $childDot = $source.IndexOf(". '__COMMON__'")
    Assert-Check ($childScripts -gt 0 -and $childDot -gt $childScripts -and $source.IndexOf('A JAR changed before the match runner started') -gt $childDot -and $source.Contains("Enter-ReleaseSprtLock 'Global\VexReleaseSprtMatch' ''")) 'the launched child verifies every hashed script before it loads one, checks both JARs, and holds the match lock'
    $idleAfter = $source.IndexOf('$idleAfter = Get-ReleaseSprtIdleReading')
    Assert-Check ($idleAfter -gt $source.IndexOf('Read-Host') -and $idleAfter -lt $source.IndexOf('Start-Process')) 'the idle CPU is measured again after the confirmation and before launch'
    $recheck = $source.IndexOf('no longer verifies')
    Assert-Check ($recheck -gt $source.IndexOf('Read-Host') -and $recheck -gt $source.IndexOf('Script changed after the identity record') -and $recheck -lt $source.IndexOf('Start-Process') -and $source.Contains('Find-ReleaseSprtApprovedSmoke $resultsDirectory $head $scripts)')) 'both gates are checked again after the confirmation and before launch'
    Assert-Check ($source.Contains('RUN-RELEASE-SMOKE') -and $source.Contains('RUN-RELEASE-SPRT') -and $source.Contains('-SmokeNative')) 'smoke and production keep their own confirmation phrases'
    Assert-Check ($source -notmatch '\$PSCommandPath|&\s+\$MyInvocation|Invoke-Expression|-ExecuteNative\s+-') 'no mode can start another mode'
    Assert-Check ($source.Contains('$config.SmokeOpeningRelativePath') -and $source.Contains('$config.OpeningRelativePath') -and ([regex]::Matches($source, 'Join-Path \$repo ''[^'']*openings')).Count -eq 0) 'opening lists are selected through the config only'
    Assert-Check ($source.Contains('Get-ReleaseSprtScriptNames') -and $source -notmatch "'release-sprt-openings.py'") 'the hashed script list has one definition'
    Write-Host "Release SPRT synthetic validation PASS: $script:checks checks. No engines, Cute Chess or games launched."
}
finally {
    $env:JAVA = $oldJava; $env:CUTECHESS = $oldCute
    Remove-Item -LiteralPath $temp -Recurse -Force
}
