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
        SmokeOpeningRelativePath = 'docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0/openings-smoke-v1.epd'
        SmokeOpeningSha256 = '51ed4dc523be6e29c28c6693ad6022478b142465b699df3397c53537cd6ca195'
        SmokeGames = 20
        PreregistrationRelativePath = 'docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0/preregistration.md'
        PreregistrationSha256 = '190326d037a7588258798d16c60d473c0c3c72e41a94aaa157a7c1b88072555c'
        Elo0 = 0; Elo1 = 20; Alpha = 0.05; Beta = 0.05; BonferroniM = 1
        MinGames = 0; MaxGames = 20000; TC = '5+0.05'; Concurrency = 6; EngineThreads = 1
        OpeningOrder = 'sequential'
        # v0.5.7 has no EvalType option (Classical is its only evaluator), so only v0.6.0 is sent it.
        NewOptions = @($shared[0..1] + 'EvalType=Classical' + $shared[2..6])
        OldOptions = @($shared)
        IdleCpuMeanLimitPercent = 5
        MaxPreflightAgeHours = 24
    }
}

function Get-ReleaseSprtParameters([string]$Directory, [string]$Openings, [switch]$Smoke) {
    $config = Get-ReleaseSprtConfig
    $parameters = @{
        New = Join-Path $Directory 'candidate-v0.6.0.jar'; Old = Join-Path $Directory 'baseline-v0.5.7.jar'
        NewOptions = @($config.NewOptions); OldOptions = @($config.OldOptions)
        OpeningsFile = $Openings; Tag = $(if ($Smoke) { 'release-strength-smoke' } else { 'release-strength' })
        LogPath = Join-Path $Directory 'match.log'; PgnPath = Join-Path $Directory 'match.pgn'
    }
    foreach ($key in @('Elo0','Elo1','Alpha','Beta','BonferroniM','MinGames','MaxGames','TC','Concurrency','EngineThreads','OpeningOrder')) {
        $parameters[$key] = $config[$key]
    }
    if ($Smoke) { $parameters.MaxGames = $config.SmokeGames }
    return $parameters
}

function Assert-ReleaseSprtParameters($Parameters, [switch]$Smoke) {
    $config = Get-ReleaseSprtConfig
    if ($Smoke) { $config.MaxGames = $config.SmokeGames }
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

function Assert-ReleaseSprtArguments($Manifest, $Parameters, [string]$Java, [string]$Cutechess, [switch]$Smoke) {
    Assert-ReleaseSprtParameters $Parameters -Smoke:$Smoke
    $games = if ($Smoke) { [string](Get-ReleaseSprtConfig).SmokeGames } else { '20000' }
    $expected = @()
    foreach ($arm in @(@{ Name='NEW'; Jar=$Parameters.New; Options=$Parameters.NewOptions },
                        @{ Name='OLD'; Jar=$Parameters.Old; Options=$Parameters.OldOptions })) {
        $expected += @('-engine', "name=$($arm.Name)", "cmd=$Java", 'arg=--add-modules',
            'arg=jdk.incubator.vector', 'arg=-jar', "arg=$($arm.Jar)", 'proto=uci', 'option.Threads=1')
        $expected += @($arm.Options | ForEach-Object { "option.$_" })
    }
    $expected += @('-each','tc=5+0.05','-games',$games,'-repeat','-recover',
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

function Assert-ReleaseSprtOpenings([string]$Path, [int]$Count = 10000) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes -contains 13) { throw 'Opening list must use LF line endings.' }
    $lines = [Text.Encoding]::ASCII.GetString($bytes).TrimEnd("`n") -split "`n"
    if ($lines.Count -ne $Count) { throw "Expected $Count openings, found $($lines.Count)." }
    if (@($lines | Select-Object -Unique).Count -ne $lines.Count) { throw 'Opening list contains duplicates.' }
    # Cute Chess rewrites the move counters in the PGN FEN tag, so two lines that differ only there would merge.
    if (@($lines | ForEach-Object { ($_ -split ' ')[0..3] -join ' ' } | Select-Object -Unique).Count -ne $lines.Count) { throw 'Opening list contains positions that differ only in move counters.' }
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

# Production verdict. Read-Stage6Results only calls the cap INCONCLUSIVE when 20000 games were scored. The preregistered
# cap is on game STARTS, so games cancelled at the end count toward it. Cute Chess alone decides H0 or H1: this never
# turns the cap, or anything else, into a hypothesis decision.
function Get-ReleaseSprtVerdict($Result, [int]$GameCap = 20000) {
    $verdict = $Result.Verdict
    if ($verdict -eq 'INCOMPLETE' -and $Result.FinishedMatch -and $Result.ExitCode -eq 0 -and $Result.Decision -eq '' -and
        @($Result.FaultLines).Count -eq 0 -and @($Result.AuditIssues).Count -eq 0 -and ($Result.Scored + $Result.Cancelled) -eq $GameCap) {
        $verdict = 'INCONCLUSIVE'
    }
    return $verdict
}

# Smoke verdict. A smoke test is not a result: it passes when Cute Chess ran both JARs to a clean finish.
function Get-ReleaseSprtSmokeVerdict($Result) {
    if (@($Result.FaultLines).Count -eq 0 -and @($Result.AuditIssues).Count -eq 0 -and $Result.ExitCode -eq 0 -and $Result.FinishedMatch -and
        $Result.Scored -gt 0 -and $Result.CompletedOpeningPairs -ge 1 -and ($Result.Cancelled -gt 0 -or $Result.UnpairedScoredGames -eq 0) -and
        $Result.Verdict -notin @('SETUP_PROTOCOL_FAULT', 'INTERRUPTED')) { return 'SMOKE_PASSED' }
    return 'SMOKE_FAILED'
}

# The smoke test is only allowed after a native preflight passed on this exact code. Returns the qualifying preflight
# directory name, or $null. The preflight's own evidence must still verify against its SHA256SUMS.
function Find-ReleaseSprtPassedPreflight([string]$ResultsRoot, [string]$Head, $ScriptHashes, [datetime]$Now = [datetime]::UtcNow) {
    if (-not (Test-Path -LiteralPath $ResultsRoot)) { return $null }
    foreach ($dir in @(Get-ChildItem -LiteralPath $ResultsRoot -Directory -Filter 'preflight-*' | Sort-Object Name -Descending)) {
        $summary = Join-Path $dir.FullName 'summary.json'; $identity = Join-Path $dir.FullName 'identity.json'; $sums = Join-Path $dir.FullName 'SHA256SUMS'; $started = Join-Path $dir.FullName 'attempt-start.json'
        if (-not ((Test-Path -LiteralPath $summary) -and (Test-Path -LiteralPath $identity) -and (Test-Path -LiteralPath $sums) -and (Test-Path -LiteralPath $started))) { continue }
        $startedUtc = [datetime]::Parse((Get-Content -LiteralPath $started -Raw | ConvertFrom-Json).StartedUtc, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
        if (($Now - $startedUtc.ToUniversalTime()).TotalHours -gt (Get-ReleaseSprtConfig).MaxPreflightAgeHours -or $startedUtc -gt $Now.AddMinutes(5)) { continue }
        if ((Get-Content -LiteralPath $summary -Raw | ConvertFrom-Json).Verdict -cne 'PREFLIGHT_PASSED') { continue }
        $id = Get-Content -LiteralPath $identity -Raw | ConvertFrom-Json
        if ($id.InvokingHead -cne $Head) { continue }
        $same = $true
        foreach ($name in $ScriptHashes.Keys) { if ($id.ScriptSha256.$name -cne $ScriptHashes[$name]) { $same = $false } }
        if (-not $same) { continue }
        # The manifest must be non-empty and list every file present, or a truncated manifest would hide tampering.
        $lines = @(Get-Content -LiteralPath $sums | Where-Object { $_ -ne '' })
        $intact = $lines.Count -gt 0
        $listed = @()
        foreach ($line in $lines) {
            $parts = $line -split '  ', 2
            if ($parts.Count -ne 2) { $intact = $false; continue }
            $listed += $parts[1]
            $file = Join-Path $dir.FullName $parts[1]
            if (-not (Test-Path -LiteralPath $file) -or (Get-Stage6Hash $file) -cne $parts[0]) { $intact = $false }
        }
        $present = @(Get-ChildItem -LiteralPath $dir.FullName -File | Where-Object { $_.Name -ne 'SHA256SUMS' } | ForEach-Object { $_.Name })
        if (@($present | Where-Object { $listed -cnotcontains $_ }).Count -gt 0) { $intact = $false }
        if ($intact) { return $dir.Name }
    }
    return $null
}
