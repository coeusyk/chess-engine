<#
.SYNOPSIS
    Prepare and execute the frozen Phase 21 Stage 6 native match only when explicitly requested.
.PARAMETER ValidateOnly
    Synthetic configuration, argument, parser and failure checks. No host probes or native tools.
.PARAMETER ExecuteNative
    Native-only builds, evidence freeze, operator confirmation, then one approved SPRT.
#>
[CmdletBinding()]
param([switch]$ValidateOnly, [switch]$ExecuteNative)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSNativeCommandUseErrorActionPreference = $false
. (Join-Path $PSScriptRoot 'phase21-stage6-common.ps1')

function Invoke-Stage6Checked([string]$Exe, [string[]]$Arguments, [string]$Directory, [string]$LogPath) {
    $writer = New-Object IO.StreamWriter($LogPath, $false)
    $writer.AutoFlush = $true
    $captured = New-Object System.Collections.Generic.List[string]
    $code = 1
    Push-Location $Directory
    $previousPreference=$ErrorActionPreference
    # Windows PowerShell 5.1 represents redirected native stderr as ErrorRecords.
    $ErrorActionPreference='Continue'
    try {
        & $Exe @Arguments 2>&1 | ForEach-Object -ErrorAction Stop { $line=$_.ToString(); $writer.WriteLine($line); $captured.Add($line); Write-Host $line }
        $code = $LASTEXITCODE
    }
    finally { $ErrorActionPreference=$previousPreference; Pop-Location; $writer.Dispose() }
    if ($code -ne 0) { throw "Command failed ($code): $Exe. Output retained at $LogPath" }
    return ($captured.ToArray() -join "`n")
}

function Get-Stage6Git([string]$Repository, [string[]]$Arguments) {
    $previousPreference=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    try { $value = & git.exe -C $Repository @Arguments 2>&1 }
    finally { $ErrorActionPreference=$previousPreference }
    if ($LASTEXITCODE -ne 0) { throw "Git failed: $($Arguments -join ' '): $($value -join '`n')" }
    return (($value | ForEach-Object { $_.ToString() }) -join "`n").Trim()
}

function Assert-Stage6Clean([string]$Repository, [switch]$Invoking) {
    $status = Get-Stage6Git $Repository @('status','--porcelain=v1','--untracked-files=all')
    $dirty = @($status -split '\r?\n' | Where-Object { $_ -and -not ($Invoking -and $_ -match '^\?\? \.claude/agent-memory/') })
    if ($dirty.Count) { throw "Dirty checkout: $Repository`: $($dirty -join '; ')" }
}

function Get-Stage6Environment([string]$Repository, [string]$Output) {
    $os=Get-CimInstance Win32_OperatingSystem; $cpu=Get-CimInstance Win32_Processor | Select-Object -First 1
    $proc=Get-Process -Id $PID
    $java=(Get-Command java.exe -CommandType Application).Source
    $maven=(Get-Command mvn -CommandType Application).Source
    $cute=if ($env:CUTECHESS) { (Resolve-Path -LiteralPath $env:CUTECHESS).Path } else { (Get-Command cutechess-cli.exe -CommandType Application).Source }
    foreach ($path in @($java,$maven,$cute)) { if ($path -notmatch '^[A-Za-z]:\\' -or -not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Non-native or missing tool: $path" } }
    foreach ($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS')) {
        if ([Environment]::GetEnvironmentVariable($name)) { throw "Unset inherited JVM option variable $name before Stage 6." }
    }
    $power = Invoke-Stage6Checked 'powercfg.exe' @('/getactivescheme') $Repository (Join-Path $Output 'power-plan.log')
    $javaRaw = Invoke-Stage6Checked $java @('-XshowSettings:properties','-version') $Repository (Join-Path $Output 'java-version.log')
    $mavenRaw = Invoke-Stage6Checked $maven @('-version') $Repository (Join-Path $Output 'maven-version.log')
    $cuteRaw = Invoke-Stage6Checked $cute @('--version') $Repository (Join-Path $Output 'cutechess-version.log')
    $facts = [pscustomobject]@{
        Platform=[Environment]::OSVersion.Platform.ToString(); ComputerName=$env:COMPUTERNAME; CpuName=$cpu.Name
        OsCaption=$os.Caption; OsBuild=$os.BuildNumber; OsVersion=$os.Version
        PowerPlanRaw=$power; AffinityMask=$proc.ProcessorAffinity.ToInt64(); FullAffinityMask=([long]1 -shl $cpu.NumberOfLogicalProcessors)-1
        HardwareLogicalProcessors=$cpu.NumberOfLogicalProcessors
        PowerShellExecutable=$proc.Path; PowerShellVersion=$PSVersionTable.PSVersion.ToString()
        JavaExecutable=$java; JavaSha256=Get-Stage6Hash $java
        JavaVersion=[regex]::Match($javaRaw,'(?m)^\s*java\.version\s*=\s*(.+?)\s*$').Groups[1].Value
        JavaRuntime=[regex]::Match($javaRaw,'(?m)^\s*java\.runtime\.version\s*=\s*(.+?)\s*$').Groups[1].Value
        JavaVendor=[regex]::Match($javaRaw,'(?m)^\s*java\.vendor\s*=\s*(.+?)\s*$').Groups[1].Value
        JavaHome=[regex]::Match($javaRaw,'(?m)^\s*java\.home\s*=\s*(.+?)\s*$').Groups[1].Value
        MavenExecutable=$maven; MavenSha256=Get-Stage6Hash $maven
        MavenVersion=[regex]::Match($mavenRaw,'(?m)^Apache Maven (\d+\.\d+\.\d+)').Groups[1].Value
        MavenJavaHome=[regex]::Match($mavenRaw,'(?m)^Java version: 21\.0\.10, vendor: Azul[^\r\n]*, runtime: (.+?)\s*$').Groups[1].Value
        CutechessExecutable=$cute; CutechessSha256=Get-Stage6Hash $cute
        CutechessVersion=[regex]::Match($cuteRaw,'(?m)^cutechess-cli (\d+\.\d+\.\d+)(?:\s|$)').Groups[1].Value
        CapturedUtc=[DateTime]::UtcNow.ToString('o'); InheritedJvmOptions='all four checked variables unset'
    }
    Save-Stage6Json $facts (Join-Path $Output 'environment.json')
    Assert-Stage6HostFacts $facts
    return $facts
}

function Assert-Stage6Jar([string]$Path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry=$zip.GetEntry('META-INF/MANIFEST.MF')
        if (-not $entry -or -not $zip.GetEntry('coeusyk/game/chess/uci/UciApplication.class') -or
            -not $zip.GetEntry('coeusyk/game/chess/core/search/Searcher.class')) { throw "Not an executable Vex JAR: $Path" }
        $reader=New-Object IO.StreamReader($entry.Open())
        try { if ($reader.ReadToEnd() -notmatch '(?m)^Main-Class: coeusyk\.game\.chess\.uci\.UciApplication\s*$') { throw "Wrong JAR main class: $Path" } }
        finally { $reader.Dispose() }
    }
    finally { $zip.Dispose() }
}

function Stop-Stage6OwnedProcess($Process, [string]$Output) {
    if ($null -ne $Process -and -not $Process.HasExited) {
        & taskkill.exe /PID $Process.Id /T /F 2>&1 | Set-Content -LiteralPath (Join-Path $Output 'owned-process-stop.log') -Encoding UTF8
        $Process.WaitForExit(10000) | Out-Null
        if (-not $Process.HasExited) { throw "Owned runner process $($Process.Id) did not stop. Do not start another match." }
    }
}

function Clear-Stage6BuildWorktrees([string]$Repository, $Worktrees, [string]$Root, [bool]$Created) {
    foreach ($path in $Worktrees) {
        try { $null=Get-Stage6Git $Repository @('worktree','remove',$path) } catch { Write-Warning "Preserving temporary worktree $path`: $_" }
    }
    if ($Created -and (Test-Path -LiteralPath $Root) -and @(Get-ChildItem -LiteralPath $Root -Force).Count -eq 0) { Remove-Item -LiteralPath $Root }
}

if ($ValidateOnly -and $ExecuteNative) { throw 'Choose exactly one mode.' }
if ($ValidateOnly) { & (Join-Path $PSScriptRoot 'phase21-stage6-validation.ps1'); return }
if (-not $ExecuteNative) { throw 'No action taken. Use -ValidateOnly for fixtures or -ExecuteNative for the approved native run.' }
if ($env:WSL_INTEROP -or $env:WSL_DISTRO_NAME -or [Environment]::OSVersion.Platform -ne 'Win32NT') { throw 'Run Stage 6 from a native Windows PowerShell terminal, never WSL/interop.' }
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ($repo -notmatch '^[A-Za-z]:\\' -or $repo -match '(?i)wsl') { throw 'The checkout must be on a native Windows drive.' }
if ((Get-Stage6Git $repo @('branch','--show-current')) -ne 'phase/21-zero-window-search') { throw 'Wrong invoking branch.' }
Assert-Stage6Clean $repo -Invoking
$config=Get-Stage6Config
$runId=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$output=Join-Path $repo "tools\results\phase21-stage6\$runId"
$workRoot=Join-Path $env:TEMP "phase21-stage6-$runId"
if ((Test-Path -LiteralPath $output) -or (Test-Path -LiteralPath $workRoot)) { throw 'Run directory collision; nothing overwritten.' }
New-Item -ItemType Directory -Path $output | Out-Null
$worktrees=New-Object System.Collections.Generic.List[string]
$child=$null; $exitCode=-1; $failure=''; $matchStarted=$false; $matchCompleted=$false; $transcript=$false; $workRootCreated=$false
$oldJava=$env:JAVA; $oldCute=$env:CUTECHESS
try {
    Start-Transcript -LiteralPath (Join-Path $output 'runner.log') | Out-Null; $transcript=$true
    Save-Stage6Json $config (Join-Path $output 'approved-config.json')
    $environment=Get-Stage6Environment $repo $output
    $env:JAVA=$environment.JavaHome; $env:CUTECHESS=$environment.CutechessExecutable
    $opening=Join-Path $repo 'tools\noob_3moves.epd'
    if ((Get-Stage6Hash $opening) -ne $config.OpeningSha256) { throw 'Opening corpus missing or incorrect.' }
    $openingCopy=Join-Path $output 'noob_3moves.epd'; Copy-Item -LiteralPath $opening -Destination $openingCopy
    if ((Get-Stage6Hash $openingCopy) -ne $config.OpeningSha256) { throw 'Opening archive copy differs.' }
    New-Item -ItemType Directory -Path $workRoot | Out-Null
    $workRootCreated=$true
    $sources=@()
    foreach ($arm in @(@{ Name='control'; Commit=$config.ControlCommit }, @{ Name='candidate'; Commit=$config.CandidateCommit })) {
        $path=Join-Path $workRoot $arm.Name
        $null=Invoke-Stage6Checked 'git.exe' @('-C',$repo,'worktree','add','--detach',$path,$arm.Commit) $repo (Join-Path $output "$($arm.Name)-checkout.log")
        $worktrees.Add($path)
        if ((Get-Stage6Git $path @('rev-parse','HEAD')) -ne $arm.Commit) { throw 'Production source SHA mismatch.' }
        Assert-Stage6Clean $path
        $source=[pscustomobject]@{ Arm=$arm.Name; Commit=$arm.Commit; Jar=$null; JarSha256=$null; SourceClean=$true }
        $sources += $source
        Save-Stage6Json $sources (Join-Path $output 'sources.json')
        foreach ($target in @('engine-core\target','engine-uci\target')) { if (Test-Path -LiteralPath (Join-Path $path $target)) { throw 'Fresh worktree contains stale build outputs.' } }
        $null=Invoke-Stage6Checked $environment.MavenExecutable @('-B','-pl','engine-core,engine-uci','-am','package','-DskipTests') $path (Join-Path $output "$($arm.Name)-build.log")
        Assert-Stage6Clean $path
        if ((Get-Stage6Git $path @('rev-parse','HEAD')) -ne $arm.Commit) { throw 'Production source SHA changed during build.' }
        $built=Join-Path $path 'engine-uci\target\engine-uci-0.6.0-SNAPSHOT.jar'
        Assert-Stage6Jar $built
        $jar=Join-Path $output "$($arm.Name).jar"; Copy-Item -LiteralPath $built -Destination $jar
        $sha=Get-Stage6Hash $jar
        if ($sha -ne (Get-Stage6Hash $built)) { throw 'Executable JAR copy mismatch.' }
        $source.Jar=$jar; $source.JarSha256=$sha
        Save-Stage6Json $sources (Join-Path $output 'sources.json')
    }
    if ($sources[0].JarSha256 -eq $sources[1].JarSha256) { throw 'Distinct production commits unexpectedly produced identical binaries.' }
    $runner=Join-Path $PSScriptRoot 'sprt.ps1'; $runnerSha=Get-Stage6Hash $runner
    $parameters=Get-Stage6Parameters $output $openingCopy
    Assert-Stage6Parameters $parameters
    $prepared=Join-Path $output 'prepared-arguments.json'
    & $runner @parameters -PrepareOnly -ArgumentManifestPath $prepared
    Assert-Stage6Arguments (Get-Content -LiteralPath $prepared -Raw | ConvertFrom-Json) $parameters $environment.JavaExecutable $environment.CutechessExecutable
    $parameters.ArgumentManifestPath=Join-Path $output 'arguments.json'
    $parameters.ExpectedArgumentManifestPath=$prepared
    $parameterPath=Join-Path $output 'runner-parameters.json'
    Save-Stage6Json $parameters $parameterPath
    $parameterSha=Get-Stage6Hash $parameterPath; $preparedSha=Get-Stage6Hash $prepared
    Save-Stage6Json ([ordered]@{ InvokingHead=Get-Stage6Git $repo @('rev-parse','HEAD'); Config=$config; Sources=$sources;
        OpeningSha256=$config.OpeningSha256; RunnerSha256=$runnerSha; WrapperSha256=Get-Stage6Hash $PSCommandPath;
        CommonSha256=Get-Stage6Hash (Join-Path $PSScriptRoot 'phase21-stage6-common.ps1'); ParametersSha256=$parameterSha;
        PreparedArgumentsSha256=$preparedSha; RandomSeed='not exposed by existing runner; actual opening order retained in PGN' }) (Join-Path $output 'identity.json')
    Write-Host "Evidence and frozen binaries: $output"
    if ((Read-Host 'Confirm idle native host: type RUN-PHASE21-STAGE6 to start the first game') -cne 'RUN-PHASE21-STAGE6') { throw 'Operator did not confirm. No games started.' }
    foreach ($arm in $sources) { if ((Get-Stage6Hash $arm.Jar) -ne $arm.JarSha256) { throw 'Frozen binary changed before launch.' } }
    foreach ($arm in @(@{Name='control';Commit=$config.ControlCommit},@{Name='candidate';Commit=$config.CandidateCommit})) {
        $path=Join-Path $workRoot $arm.Name; Assert-Stage6Clean $path
        if ((Get-Stage6Git $path @('rev-parse','HEAD')) -ne $arm.Commit) { throw 'Frozen source changed before launch.' }
    }
    if ((Get-Stage6Hash $parameterPath) -ne $parameterSha -or (Get-Stage6Hash $prepared) -ne $preparedSha) { throw 'Frozen parameter/argument manifest changed before launch.' }
    if ((Get-Stage6Hash $runner) -ne $runnerSha -or (Get-Stage6Hash $openingCopy) -ne $config.OpeningSha256 -or
        (Get-Stage6Hash $environment.JavaExecutable) -ne $environment.JavaSha256 -or
        (Get-Stage6Hash $environment.CutechessExecutable) -ne $environment.CutechessSha256) { throw 'Frozen input/tool changed before launch.' }
    $environment.PowerPlanRaw=Invoke-Stage6Checked 'powercfg.exe' @('/getactivescheme') $repo (Join-Path $output 'launch-power-plan.log')
    $environment.AffinityMask=(Get-Process -Id $PID).ProcessorAffinity.ToInt64()
    Assert-Stage6HostFacts $environment
    Save-Stage6Json @{ ConfirmedUtc=[DateTime]::UtcNow.ToString('o'); IdleHostConfirmed=$true } (Join-Path $output 'launch-confirmation.json')
    $launch = @'
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$p=Get-Content -LiteralPath '__PARAMETERS__' -Raw | ConvertFrom-Json
$argsTable=@{}; foreach($property in $p.psobject.Properties){$argsTable[$property.Name]=$property.Value}
if ((Get-Process -Id $PID).ProcessorAffinity.ToInt64() -ne __AFFINITY__) { throw 'Child runner does not have frozen full affinity.' }
try { & '__RUNNER__' @argsTable; exit 0 } catch { Write-Error $_ -ErrorAction Continue; exit 1 }
'@
    $launch=$launch.Replace('__PARAMETERS__',(Join-Path $output 'runner-parameters.json').Replace("'","''")).Replace('__RUNNER__',$runner.Replace("'","''"))
    $launch=$launch.Replace('__AFFINITY__',[string]$environment.FullAffinityMask)
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($launch))
    Set-Content -LiteralPath $parameters.LogPath -Value '' -NoNewline -Encoding UTF8
    $child=Start-Process -FilePath $environment.PowerShellExecutable -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded) -WorkingDirectory $repo -PassThru -RedirectStandardOutput (Join-Path $output 'console.stdout.log') -RedirectStandardError (Join-Path $output 'console.stderr.log')
    $matchStarted=$true
    Save-Stage6Json @{ ProcessId=$child.Id; StartedUtc=[DateTime]::UtcNow.ToString('o') } (Join-Path $output 'owned-runner.json')
    $reader=New-Object IO.StreamReader([IO.File]::Open($parameters.LogPath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite))
    $pending=''
    try {
        while (-not $child.HasExited) {
            $pending += $reader.ReadToEnd()
            $lines=$pending -split "`n"; $pending=$lines[$lines.Count-1]
            for ($n=0; $n -lt $lines.Count-1; $n++) {
                Write-Host $lines[$n]
                if (Test-Stage6FaultLine $lines[$n]) { throw 'Match fault detected; stopping the owned process tree. Inspect preserved evidence.' }
            }
            Start-Sleep -Milliseconds 200
        }
        $child.WaitForExit()
        if ($null -eq $child.ExitCode) { throw 'Owned runner exit code is unavailable; result cannot be accepted.' }
        $exitCode=$child.ExitCode; $matchCompleted=$true
    }
    finally { $reader.Dispose() }
}
catch { $failure=$_.ToString(); Write-Warning $failure }
finally {
    try { Stop-Stage6OwnedProcess $child $output } catch { $failure += "`n$_" }
    $env:JAVA=$oldJava; $env:CUTECHESS=$oldCute
    $logPath=Join-Path $output 'match.log'; $pgnPath=Join-Path $output 'match.pgn'
    try {
        $log=if (Test-Path -LiteralPath $logPath) { [string](Get-Content -LiteralPath $logPath -Raw -Encoding UTF8) } else { '' }
        $pgn=if (Test-Path -LiteralPath $pgnPath) { [string](Get-Content -LiteralPath $pgnPath -Raw -Encoding UTF8) } else { '' }
        $result=Read-Stage6Results $log $pgn $exitCode ($matchStarted -and -not $matchCompleted)
        if ($failure -and $result.Verdict -ne 'INTERRUPTED') { $result.Verdict='SETUP_PROTOCOL_FAULT'; $result.EligibleForPromotion=$false }
        $result | Add-Member -NotePropertyName Failure -NotePropertyValue $failure
        $result.PairAudit | Export-Csv -LiteralPath (Join-Path $output 'pair-audit.csv') -NoTypeInformation -Encoding UTF8
        Save-Stage6Json $result (Join-Path $output 'summary.json')
        Write-Host "Stage 6 result: $($result.Verdict). Evidence review required; no automatic promotion."
    }
    catch { Save-Stage6Json @{ Verdict='SETUP_PROTOCOL_FAULT'; EligibleForPromotion=$false; Failure="$failure`n$_" } (Join-Path $output 'summary.json'); $failure += "`n$_" }
    Clear-Stage6BuildWorktrees $repo $worktrees $workRoot $workRootCreated
    if ($transcript) { Stop-Transcript | Out-Null }
    Get-ChildItem -LiteralPath $output -File | Where-Object { $_.Name -ne 'SHA256SUMS' } | Sort-Object Name |
        ForEach-Object { '{0}  {1}' -f (Get-Stage6Hash $_.FullName),$_.Name } | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS') -Encoding ASCII
}
if ($failure) { throw "Stage 6 stopped. Evidence retained at $output. $failure" }
