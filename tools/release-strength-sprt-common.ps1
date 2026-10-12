# Pure configuration and checking helpers for the v0.5.7 vs v0.6.0 release SPRT. This file never starts a process
# except Invoke-ReleaseSprtHandshake, which only sends uci/setoption/isready/quit to an engine (no game, no search).
# Log and PGN parsing is reused from phase21-stage6-common.ps1 (Read-Stage6Results), which already checks 5+0.05,
# NEW/OLD names, the 20000-game cap and ln(19) bounds. Those all hold for this experiment.
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'phase21-stage6-common.ps1')

function Get-ReleaseSprtConfig {
    $shared = @('Hash=16', 'PawnHashSize=1', 'MultiPV=1', 'Contempt=0', 'OwnBook=false', 'SyzygyOnline=false', 'SyzygyPath=')
    return [ordered]@{
        CandidateAsset = 'engine-uci-0.6.0.jar'
        CandidateSha256 = 'e16163b8fe6f8278161db0d9220883c5d49a4814df0d81f7a6cad901c07cf4e7'
        BaselineAsset = 'engine-uci-0.5.7.jar'
        BaselineSha256 = 'bc41c91b0d3b4c20f6a334ff3f0cc2cf5c04e6820b817b1cfd46e62a640663d3'
        OpeningRelativePath = 'docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0/openings-v1.epd'
        OpeningSha256 = '32a2dc9b6ee1fee14a44ebb915441a2401e54276f75424804fe70f7f48354e1d'
        PreregistrationRelativePath = 'docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0/preregistration.md'
        PreregistrationSha256 = '9eeeef6d6b232f03d8466988556119c0008ca420341ee145288facdc970612b1'
        Elo0 = 0; Elo1 = 20; Alpha = 0.05; Beta = 0.05; BonferroniM = 1
        MinGames = 0; MaxGames = 20000; TC = '5+0.05'; Concurrency = 6; EngineThreads = 1
        OpeningOrder = 'sequential'
        # v0.5.7 has no EvalType option (Classical is its only evaluator), so only v0.6.0 is sent it.
        NewOptions = @($shared[0..1] + 'EvalType=Classical' + $shared[2..6])
        OldOptions = @($shared)
        IdleCpuMeanLimitPercent = 5
    }
}

function Get-ReleaseSprtParameters([string]$Directory, [string]$Openings) {
    $config = Get-ReleaseSprtConfig
    $parameters = @{
        New = Join-Path $Directory 'candidate-v0.6.0.jar'; Old = Join-Path $Directory 'baseline-v0.5.7.jar'
        NewOptions = @($config.NewOptions); OldOptions = @($config.OldOptions)
        OpeningsFile = $Openings; Tag = 'release-strength'
        LogPath = Join-Path $Directory 'match.log'; PgnPath = Join-Path $Directory 'match.pgn'
    }
    foreach ($key in @('Elo0','Elo1','Alpha','Beta','BonferroniM','MinGames','MaxGames','TC','Concurrency','EngineThreads','OpeningOrder')) {
        $parameters[$key] = $config[$key]
    }
    return $parameters
}

function Assert-ReleaseSprtParameters($Parameters) {
    $config = Get-ReleaseSprtConfig
    foreach ($key in @('Elo0','Elo1','Alpha','Beta','BonferroniM','MinGames','MaxGames','TC','Concurrency','EngineThreads','OpeningOrder')) {
        if (-not $Parameters.ContainsKey($key) -or $Parameters[$key] -cne $config[$key]) {
            throw "Parameter differs from the preregistration: $key"
        }
    }
    foreach ($key in @('NewOptions','OldOptions')) {
        if (($Parameters[$key] -join "`0") -cne ($config[$key] -join "`0")) { throw "Options differ from the preregistration: $key" }
    }
    $paths = @()
    foreach ($key in @('New','Old','OpeningsFile','LogPath','PgnPath')) {
        if (-not $Parameters.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($Parameters[$key])) { throw "Missing path: $key" }
        $paths += [IO.Path]::GetFullPath($Parameters[$key])
    }
    if (@($paths | Select-Object -Unique).Count -ne 5) { throw 'Inputs and output paths must be distinct.' }
}

function Assert-ReleaseSprtArguments($Manifest, $Parameters, [string]$Java, [string]$Cutechess) {
    Assert-ReleaseSprtParameters $Parameters
    $expected = @()
    foreach ($arm in @(@{ Name='NEW'; Jar=$Parameters.New; Options=$Parameters.NewOptions },
                        @{ Name='OLD'; Jar=$Parameters.Old; Options=$Parameters.OldOptions })) {
        $expected += @('-engine', "name=$($arm.Name)", "cmd=$Java", 'arg=--add-modules',
            'arg=jdk.incubator.vector', 'arg=-jar', "arg=$($arm.Jar)", 'proto=uci', 'option.Threads=1')
        $expected += @($arm.Options | ForEach-Object { "option.$_" })
    }
    $expected += @('-each','tc=5+0.05','-games','20000','-repeat','-recover',
        '-resign','movecount=5','score=400','-draw','movenumber=40','movecount=8','score=10',
        '-sprt','elo0=0','elo1=20','alpha=0.05','beta=0.05','-concurrency','6','-ratinginterval','10',
        '-pgnout',$Parameters.PgnPath,'-openings',"file=$($Parameters.OpeningsFile)",'format=epd','order=sequential','plies=4')
    if ($Manifest.Executable -cne $Cutechess -or ($Manifest.Arguments -join "`0") -cne ($expected -join "`0")) {
        throw 'Actual executable/arguments do not match the preregistered match.'
    }
}

function Assert-ReleaseSprtHostFacts($Facts) {
    if ($Facts.Platform -ne 'Win32NT' -or $Facts.ComputerName -ine 'RENEGADE' -or
        $Facts.CpuName -notmatch '^AMD Ryzen 7 7700X(?:\s|$)') { throw 'Wrong native host.' }
    if ($Facts.PowerPlanRaw -notmatch '381b4222-f694-41f0-9685-ff5bb260df2e' -or
        $Facts.AffinityMask -ne $Facts.FullAffinityMask) { throw 'Requires the Balanced power plan and full affinity.' }
    if ($Facts.JavaVersion -ne '21.0.10' -or $Facts.JavaRuntime -ne '21.0.10+7-LTS' -or
        $Facts.JavaVendor -notmatch 'Azul') { throw 'Wrong JDK.' }
    if ($Facts.CutechessVersion -ne '1.5.1') { throw 'Requires cutechess-cli 1.5.1.' }
    if ($Facts.HardwareLogicalProcessors -ne 16) { throw "Requires 16 logical processors, found $($Facts.HardwareLogicalProcessors)." }
    if ($Facts.IdleCpuMean -gt (Get-ReleaseSprtConfig).IdleCpuMeanLimitPercent) { throw "Host is not idle: mean CPU $($Facts.IdleCpuMean)%." }
}

function Test-ReleaseSprtFaultLine([string]$Line) {
    if (Test-Stage6FaultLine $Line) { return $true }
    # Wording of cutechess-cli's unsupported-option warning was not verified; match the plausible forms.
    return $Line -match "(?i)(doesn't|does not|has no|have no)\s+(have\s+)?(an?\s+)?option|option\s+\S+\s+(not found|not supported|is unknown)"
}

function Assert-ReleaseSprtOpenings([string]$Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes -contains 13) { throw 'Opening list must use LF line endings.' }
    $lines = [Text.Encoding]::ASCII.GetString($bytes).TrimEnd("`n") -split "`n"
    if ($lines.Count -ne 10000) { throw "Expected 10000 openings, found $($lines.Count)." }
    if (@($lines | Select-Object -Unique).Count -ne $lines.Count) { throw 'Opening list contains duplicates.' }
    foreach ($line in $lines) { if (($line -split ' ').Count -ne 6 -or ($line -split ' ')[1] -ne 'w') { throw "Unexpected opening line: $line" } }
}

# Returns the option names the engine advertised. Throws unless uciok and readyok were seen and every option
# in $Required was advertised and every name in $Forbidden was not.
function Assert-ReleaseSprtHandshake([string]$Output, [string[]]$Required, [string[]]$Forbidden) {
    $names = @([regex]::Matches($Output, '(?m)^option name (\S+) type') | ForEach-Object { $_.Groups[1].Value })
    if ($Output -notmatch '(?m)^id name Vex\s*$' -or $Output -notmatch '(?m)^uciok\s*$' -or $Output -notmatch '(?m)^readyok\s*$') {
        throw 'Handshake incomplete (id name Vex, uciok and readyok required).'
    }
    foreach ($name in $Required) { if ($names -cnotcontains $name) { throw "Engine did not advertise option $name." } }
    foreach ($name in $Forbidden) { if ($names -ccontains $name) { throw "Engine unexpectedly advertises option $name." } }
    return $names
}

function Get-ReleaseSprtOptionNames([string[]]$Options) {
    return @($Options | ForEach-Object { ($_ -split '=', 2)[0] })
}

function Invoke-ReleaseSprtHandshake([string]$Java, [string]$Jar, [string[]]$Options, [string]$OutputPath) {
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $Java
    $psi.Arguments = '--add-modules jdk.incubator.vector -jar "' + $Jar + '"'
    $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $lines = @('uci', 'setoption name Threads value 1')
    foreach ($o in $Options) { $pair = $o -split '=', 2; $lines += "setoption name $($pair[0]) value $($pair[1])" }
    $lines += @('isready', 'quit')
    $process = [Diagnostics.Process]::Start($psi)
    $errTask = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.Write(($lines -join "`n") + "`n"); $process.StandardInput.Close()
    $outTask = $process.StandardOutput.ReadToEndAsync()
    if (-not $process.WaitForExit(60000)) { $process.Kill(); throw "Handshake timed out: $Jar" }
    $text = ($lines | ForEach-Object { "> $_" }) -join "`n"
    $text += "`n" + $outTask.Result.Replace("`r`n", "`n") + "`n[exit code $($process.ExitCode)]`n[stderr]`n" + $errTask.Result.Replace("`r`n", "`n")
    [IO.File]::WriteAllText($OutputPath, $text, (New-Object Text.UTF8Encoding($false)))
    if ($process.ExitCode -ne 0) { throw "Engine exited with code $($process.ExitCode) during handshake: $Jar" }
    return $outTask.Result
}
