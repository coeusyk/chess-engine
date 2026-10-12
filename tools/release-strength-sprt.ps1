<#
.SYNOPSIS
    Preregistered SPRT between the published Vex v0.6.0 and v0.5.7 JARs. See
    docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0/preregistration.md.
.PARAMETER ValidateOnly
    Synthetic checks. No host probes, engines, Cute Chess or games.
.PARAMETER PreflightNative
    Native host and input qualification: host facts, idle sample, JAR and opening identities, a uci/setoption/isready
    handshake with each JAR, and the exact Cute Chess argument list. Starts no games. Writes its own attempt directory.
.PARAMETER ExecuteNative
    Everything in PreflightNative, then an operator confirmation, then the one preregistered SPRT.
.PARAMETER AssetDirectory
    Directory holding engine-uci-0.6.0.jar and engine-uci-0.5.7.jar exactly as published.
#>
[CmdletBinding()]
param([switch]$ValidateOnly, [switch]$PreflightNative, [switch]$ExecuteNative, [string]$AssetDirectory = '')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSNativeCommandUseErrorActionPreference = $false
. (Join-Path $PSScriptRoot 'release-strength-sprt-common.ps1')

# Generic process helpers are taken from the Phase 21 wrapper without running its main body.
$parseErrors = $null; $tokens = $null
$stage6Ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'phase21-stage6.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Cannot parse phase21-stage6.ps1.' }
foreach ($name in @('Invoke-Stage6Checked', 'Get-Stage6Git', 'Stop-Stage6OwnedProcess', 'Assert-Stage6Jar')) {
    $definition = $stage6Ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $false)
    . ([scriptblock]::Create($definition.Extent.Text))
}

$modes = @($ValidateOnly, $PreflightNative, $ExecuteNative) | Where-Object { $_ }
if (@($modes).Count -ne 1) { throw 'Choose exactly one of -ValidateOnly, -PreflightNative, -ExecuteNative.' }
if ($ValidateOnly) { & (Join-Path $PSScriptRoot 'release-strength-sprt-validation.ps1'); return }
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$config = Get-ReleaseSprtConfig
$kind = if ($ExecuteNative) { 'run' } else { 'preflight' }
$runId = $kind + '-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$output = Join-Path (Join-Path $repo 'tools\results\release-strength-sprt') $runId
if (Test-Path -LiteralPath $output) { throw 'Run directory collision; nothing overwritten.' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
# First write, before any other check can fail: every invocation that passes mode selection leaves a record.
function Get-AttemptGit([string[]]$Arguments) { try { return Get-Stage6Git $repo $Arguments } catch { return 'unavailable' } }
Save-Stage6Json ([ordered]@{ RunId = $runId; Kind = $kind; StartedUtc = [DateTime]::UtcNow.ToString('o'); Status = 'STARTED'
    InvokingHead = (Get-AttemptGit @('rev-parse', 'HEAD')); Branch = (Get-AttemptGit @('branch', '--show-current')) }) (Join-Path $output 'attempt-start.json')

$mutex = $null; $child = $null; $exitCode = -1; $failure = ''; $matchStarted = $false; $matchCompleted = $false; $transcript = $false; $preflightPassed = $false
$oldJava = $env:JAVA; $oldCute = $env:CUTECHESS
try {
    Start-Transcript -LiteralPath (Join-Path $output 'runner.log') | Out-Null; $transcript = $true
    Save-Stage6Json $config (Join-Path $output 'approved-config.json')

    if ($env:WSL_INTEROP -or $env:WSL_DISTRO_NAME -or [Environment]::OSVersion.Platform -ne 'Win32NT') { throw 'Run from native Windows PowerShell, never WSL/interop.' }
    if (-not $AssetDirectory) { throw 'Pass -AssetDirectory with the two published JARs.' }
    if ($repo -notmatch '^[A-Za-z]:\\' -or $repo -match '(?i)wsl') { throw 'The checkout must be on a native Windows drive.' }
    if ($config.PreregistrationSha256 -ceq 'PENDING') { throw 'Preregistration is not frozen: PreregistrationSha256 is still PENDING.' }
    # One attempt at a time on this machine: a second concurrent run would change the host load the preregistration assumes.
    # The mutex is released by the OS if this process is killed, so a dead attempt never blocks the next one.
    $mutex = New-Object Threading.Mutex($false, 'Global\VexReleaseSprt')
    if (-not $mutex.WaitOne(0)) { $mutex.Dispose(); $mutex = $null; throw 'Another release SPRT attempt is running on this machine.' }
    # The attempts directory itself is allowed to be untracked; anything else dirty blocks the run.
    $resultsRoot = 'tools/results/release-strength-sprt/'
    $status = Get-Stage6Git $repo @('status', '--porcelain=v1', '--untracked-files=all')
    $dirty = @($status -split '\r?\n' | Where-Object { $_ -and $_ -notmatch ('^\?\? ' + [regex]::Escape($resultsRoot)) -and $_ -notmatch '^\?\? \.claude/agent-memory/' })
    if ($dirty.Count) { throw "Dirty checkout: $($dirty -join '; ')" }

    foreach ($name in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS')) {
        if ([Environment]::GetEnvironmentVariable($name)) { throw "Unset inherited JVM option variable $name first." }
    }
    $os = Get-CimInstance Win32_OperatingSystem; $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1; $proc = Get-Process -Id $PID
    $java = (Get-Command java.exe -CommandType Application | Select-Object -First 1).Source
    $cute = if ($env:CUTECHESS) { (Resolve-Path -LiteralPath $env:CUTECHESS).Path } else { (Get-Command cutechess-cli.exe -CommandType Application | Select-Object -First 1).Source }
    foreach ($path in @($java, $cute)) { if ($path -notmatch '^[A-Za-z]:\\' -or -not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Non-native or missing tool: $path" } }
    $power = Invoke-Stage6Checked 'powercfg.exe' @('/getactivescheme') $repo (Join-Path $output 'power-plan.log')
    $javaRaw = Invoke-Stage6Checked $java @('-XshowSettings:properties', '-version') $repo (Join-Path $output 'java-version.log')
    $cuteRaw = Invoke-Stage6Checked $cute @('--version') $repo (Join-Path $output 'cutechess-version.log')
    $idle = (Get-Counter '\Processor Information(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 15).CounterSamples.CookedValue
    $facts = [pscustomobject]@{
        Platform = [Environment]::OSVersion.Platform.ToString(); ComputerName = $env:COMPUTERNAME; CpuName = $cpu.Name
        OsCaption = $os.Caption; OsBuild = $os.BuildNumber; OsVersion = $os.Version
        PowerPlanRaw = $power; AffinityMask = $proc.ProcessorAffinity.ToInt64(); FullAffinityMask = ([long]1 -shl $cpu.NumberOfLogicalProcessors) - 1
        HardwareLogicalProcessors = $cpu.NumberOfLogicalProcessors
        PowerShellExecutable = $proc.Path; PowerShellVersion = $PSVersionTable.PSVersion.ToString()
        JavaExecutable = $java; JavaSha256 = Get-Stage6Hash $java
        JavaVersion = [regex]::Match($javaRaw, '(?m)^\s*java\.version\s*=\s*(.+?)\s*$').Groups[1].Value
        JavaRuntime = [regex]::Match($javaRaw, '(?m)^\s*java\.runtime\.version\s*=\s*(.+?)\s*$').Groups[1].Value
        JavaVendor = [regex]::Match($javaRaw, '(?m)^\s*java\.vendor\s*=\s*(.+?)\s*$').Groups[1].Value
        JavaHome = [regex]::Match($javaRaw, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$').Groups[1].Value
        CutechessExecutable = $cute; CutechessSha256 = Get-Stage6Hash $cute
        CutechessVersion = [regex]::Match($cuteRaw, '(?m)^cutechess-cli (\d+\.\d+\.\d+)(?:\s|$)').Groups[1].Value
        IdleCpuMean = [math]::Round(($idle | Measure-Object -Average).Average, 2); IdleCpuMax = [math]::Round(($idle | Measure-Object -Maximum).Maximum, 2)
        CapturedUtc = [DateTime]::UtcNow.ToString('o'); InheritedJvmOptions = 'all four checked variables unset'
    }
    Save-Stage6Json $facts (Join-Path $output 'environment.json')
    Assert-ReleaseSprtHostFacts $facts
    $env:JAVA = $facts.JavaHome; $env:CUTECHESS = $facts.CutechessExecutable

    $prereg = Join-Path $repo $config.PreregistrationRelativePath.Replace('/', '\')
    if ((Get-Stage6Hash $prereg) -cne $config.PreregistrationSha256) { throw 'Preregistration differs from the frozen hash.' }
    Copy-Item -LiteralPath $prereg -Destination (Join-Path $output 'preregistration.md')

    $openingSource = Join-Path $repo $config.OpeningRelativePath.Replace('/', '\')
    if ((Get-Stage6Hash $openingSource) -cne $config.OpeningSha256) { throw 'Opening list missing or incorrect.' }
    Assert-ReleaseSprtOpenings $openingSource
    $openingCopy = Join-Path $output 'openings-v1.epd'; Copy-Item -LiteralPath $openingSource -Destination $openingCopy
    if ((Get-Stage6Hash $openingCopy) -cne $config.OpeningSha256) { throw 'Opening archive copy differs.' }

    $arms = @(@{ Name = 'candidate'; Asset = $config.CandidateAsset; Sha = $config.CandidateSha256; Copy = 'candidate-v0.6.0.jar'; Options = $config.NewOptions; Forbidden = @() },
              @{ Name = 'baseline'; Asset = $config.BaselineAsset; Sha = $config.BaselineSha256; Copy = 'baseline-v0.5.7.jar'; Options = $config.OldOptions; Forbidden = @('EvalType', 'EvalFile') })
    $assets = @()
    foreach ($arm in $arms) {
        $source = Join-Path $AssetDirectory $arm.Asset
        if ((Get-Stage6Hash $source) -cne $arm.Sha) { throw "Published asset hash mismatch: $($arm.Asset)" }
        Assert-Stage6Jar $source
        $copy = Join-Path $output $arm.Copy; Copy-Item -LiteralPath $source -Destination $copy
        if ((Get-Stage6Hash $copy) -cne $arm.Sha) { throw "Archive copy differs: $($arm.Copy)" }
        $required = @('Threads') + (Get-ReleaseSprtOptionNames $arm.Options)
        $text = Invoke-ReleaseSprtHandshake $java $copy $arm.Options (Join-Path $output ("uci-handshake-" + $arm.Name + ".txt"))
        $null = Assert-ReleaseSprtHandshake $text $required $arm.Forbidden
        $assets += [pscustomobject]@{ Arm = $arm.Name; Asset = $arm.Asset; Sha256 = $arm.Sha; Copy = $arm.Copy; Size = (Get-Item -LiteralPath $copy).Length }
    }
    Save-Stage6Json $assets (Join-Path $output 'assets.json')

    $runner = Join-Path $PSScriptRoot 'sprt.ps1'
    $parameters = Get-ReleaseSprtParameters $output $openingCopy
    Assert-ReleaseSprtParameters $parameters
    $prepared = Join-Path $output 'prepared-arguments.json'
    & $runner @parameters -PrepareOnly -ArgumentManifestPath $prepared
    Assert-ReleaseSprtArguments (Get-Content -LiteralPath $prepared -Raw | ConvertFrom-Json) $parameters $facts.JavaExecutable $facts.CutechessExecutable
    $parameters.ArgumentManifestPath = Join-Path $output 'arguments.json'
    $parameters.ExpectedArgumentManifestPath = $prepared
    $parameterPath = Join-Path $output 'runner-parameters.json'
    Save-Stage6Json $parameters $parameterPath
    $scripts = [ordered]@{}
    foreach ($name in @('release-strength-sprt.ps1', 'release-strength-sprt-common.ps1', 'release-strength-sprt-validation.ps1', 'phase21-stage6-common.ps1', 'phase21-stage6.ps1', 'sprt.ps1', 'release-sprt-analyze.py', 'release-sprt-openings.py')) {
        $scripts[$name] = Get-Stage6Hash (Join-Path $PSScriptRoot $name)
    }
    Save-Stage6Json ([ordered]@{ InvokingHead = Get-Stage6Git $repo @('rev-parse', 'HEAD'); Config = $config; Assets = $assets
        ScriptSha256 = $scripts; ParametersSha256 = Get-Stage6Hash $parameterPath; PreparedArgumentsSha256 = Get-Stage6Hash $prepared
        RandomSeed = 'none: sequential opening order, no cutechess -srand' }) (Join-Path $output 'identity.json')
    $preflightPassed = $true
    if ($PreflightNative) { Write-Host "Preflight passed. No games were started. Evidence: $output" }
    else {
        Write-Host "Evidence and frozen inputs: $output"
        if ((Read-Host 'Confirm idle native host: type RUN-RELEASE-SPRT to start the first game') -cne 'RUN-RELEASE-SPRT') { throw 'Operator did not confirm. No games started.' }
        foreach ($arm in $arms) { if ((Get-Stage6Hash (Join-Path $output $arm.Copy)) -cne $arm.Sha) { throw 'Frozen binary changed before launch.' } }
        foreach ($name in $scripts.Keys) { if ((Get-Stage6Hash (Join-Path $PSScriptRoot $name)) -cne $scripts[$name]) { throw "Script changed after the identity record: $name" } }
        if ((Get-Stage6Hash $openingCopy) -cne $config.OpeningSha256 -or (Get-Stage6Hash $parameterPath) -cne (Get-Content -LiteralPath (Join-Path $output 'identity.json') -Raw | ConvertFrom-Json).ParametersSha256 -or
            (Get-Stage6Hash $java) -cne $facts.JavaSha256 -or (Get-Stage6Hash $cute) -cne $facts.CutechessSha256) { throw 'Frozen input or tool changed before launch.' }
        $facts.PowerPlanRaw = Invoke-Stage6Checked 'powercfg.exe' @('/getactivescheme') $repo (Join-Path $output 'launch-power-plan.log')
        $facts.AffinityMask = (Get-Process -Id $PID).ProcessorAffinity.ToInt64()
        Assert-ReleaseSprtHostFacts $facts
        Save-Stage6Json @{ ConfirmedUtc = [DateTime]::UtcNow.ToString('o'); IdleHostConfirmed = $true } (Join-Path $output 'launch-confirmation.json')
        $launch = @'
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$p=Get-Content -LiteralPath '__PARAMETERS__' -Raw | ConvertFrom-Json
$argsTable=@{}; foreach($property in $p.psobject.Properties){$argsTable[$property.Name]=$property.Value}
if ((Get-Process -Id $PID).ProcessorAffinity.ToInt64() -ne __AFFINITY__) { throw 'Child runner does not have frozen full affinity.' }
try { & '__RUNNER__' @argsTable; exit 0 } catch { Write-Error $_ -ErrorAction Continue; exit 1 }
'@
        $launch = $launch.Replace('__PARAMETERS__', $parameterPath.Replace("'", "''")).Replace('__RUNNER__', $runner.Replace("'", "''")).Replace('__AFFINITY__', [string]$facts.FullAffinityMask)
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($launch))
        Set-Content -LiteralPath $parameters.LogPath -Value '' -NoNewline -Encoding UTF8
        $child = Start-Process -FilePath $facts.PowerShellExecutable -ArgumentList @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded) -WorkingDirectory $repo -PassThru -RedirectStandardOutput (Join-Path $output 'console.stdout.log') -RedirectStandardError (Join-Path $output 'console.stderr.log')
        $matchStarted = $true
        Save-Stage6Json @{ ProcessId = $child.Id; StartedUtc = [DateTime]::UtcNow.ToString('o') } (Join-Path $output 'owned-runner.json')
        $reader = New-Object IO.StreamReader([IO.File]::Open($parameters.LogPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite))
        $pending = ''
        try {
            while (-not $child.HasExited) {
                $pending += $reader.ReadToEnd()
                $lines = $pending -split "`n"; $pending = $lines[$lines.Count - 1]
                for ($n = 0; $n -lt $lines.Count - 1; $n++) {
                    Write-Host $lines[$n]
                    if (Test-ReleaseSprtFaultLine $lines[$n]) { throw 'Match fault detected; stopping the owned process tree. Inspect preserved evidence.' }
                }
                Start-Sleep -Milliseconds 200
            }
            $child.WaitForExit()
            if ($null -eq $child.ExitCode) { throw 'Owned runner exit code is unavailable; result cannot be accepted.' }
            $exitCode = $child.ExitCode; $matchCompleted = $true
        }
        finally { $reader.Dispose() }
    }
}
catch { $failure = $_.ToString(); Write-Warning $failure }
finally {
    try { Stop-Stage6OwnedProcess $child $output } catch { $failure += "`n$_" }
    $env:JAVA = $oldJava; $env:CUTECHESS = $oldCute
    if ($null -ne $mutex) { $mutex.ReleaseMutex(); $mutex.Dispose() }
    try {
        if ($PreflightNative) {
            $verdict = if ($preflightPassed -and -not $failure) { 'PREFLIGHT_PASSED' } else { 'PREFLIGHT_FAILED' }
            Save-Stage6Json ([ordered]@{ Verdict = $verdict; Failure = $failure; GamesStarted = 0 }) (Join-Path $output 'summary.json')
        }
        else {
            $logPath = Join-Path $output 'match.log'; $pgnPath = Join-Path $output 'match.pgn'
            $log = if (Test-Path -LiteralPath $logPath) { [string](Get-Content -LiteralPath $logPath -Raw -Encoding UTF8) } else { '' }
            $pgn = if (Test-Path -LiteralPath $pgnPath) { [string](Get-Content -LiteralPath $pgnPath -Raw -Encoding UTF8) } else { '' }
            $result = Read-Stage6Results $log $pgn $exitCode ($matchStarted -and -not $matchCompleted)
            if ($failure -and $result.Verdict -ne 'INTERRUPTED') { $result.Verdict = 'SETUP_PROTOCOL_FAULT'; $result.EligibleForPromotion = $false }
            if (-not $matchStarted -and $failure) { $result.Verdict = 'NOT_STARTED' }
            $result | Add-Member -NotePropertyName Failure -NotePropertyValue $failure
            $result.PairAudit | Export-Csv -LiteralPath (Join-Path $output 'pair-audit.csv') -NoTypeInformation -Encoding UTF8
            Save-Stage6Json $result (Join-Path $output 'summary.json')
            Write-Host "Result: $($result.Verdict). Evidence review required; analyze with tools/release-sprt-analyze.py."
        }
    }
    catch { $failure += "`n$_"; Save-Stage6Json @{ Verdict = 'SUMMARY_FAILED'; Failure = $failure } (Join-Path $output 'summary.json') }
    Save-Stage6Json ([ordered]@{ RunId = $runId; Kind = $kind; EndedUtc = [DateTime]::UtcNow.ToString('o'); MatchStarted = $matchStarted; MatchCompleted = $matchCompleted
        ExitCode = $exitCode; Failure = $failure }) (Join-Path $output 'attempt-end.json')
    if ($transcript) { Stop-Transcript | Out-Null }
    Get-ChildItem -LiteralPath $output -File | Where-Object { $_.Name -ne 'SHA256SUMS' } | Sort-Object Name |
        ForEach-Object { '{0}  {1}' -f (Get-Stage6Hash $_.FullName), $_.Name } | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS') -Encoding ASCII
}
if ($failure) { throw "Stopped. Evidence retained at $output. $failure" }
