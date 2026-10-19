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
    # Written so that NaN, infinity, a missing value and a string all fail: every comparison with NaN is false, and $null -ge 0 is true.
    $idle = $Facts.IdleCpuMean
    if (-not (($idle -is [double] -or $idle -is [int]) -and $idle -ge 0 -and $idle -le (Get-ReleaseSprtConfig).IdleCpuMeanLimitPercent)) { throw "Host is not idle (or the reading is not a number): mean CPU $idle%." }
}

# Mean and maximum of the idle CPU readings. Only a full set of finite numbers is accepted, so an empty or broken counter cannot read as an idle host.
function Measure-ReleaseSprtIdle($Samples, [int]$Expected = 15) {
    $values = @($Samples)
    if ($values.Count -lt $Expected) { throw "The idle CPU sample is incomplete: $($values.Count) of $Expected readings." }
    foreach ($value in $values) {
        if (-not (($value -is [double] -or $value -is [int]) -and -not [double]::IsNaN([double]$value) -and -not [double]::IsInfinity([double]$value))) { throw "The idle CPU sample holds a reading that is not a finite number: '$value'." }
    }
    $measured = $values | Measure-Object -Average -Maximum
    return [pscustomobject]@{ Mean = [math]::Round($measured.Average, 2); Max = [math]::Round($measured.Maximum, 2) }
}

function Get-ReleaseSprtIdleReading {
    return (Measure-ReleaseSprtIdle @((Get-Counter '\Processor Information(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 15).CounterSamples.CookedValue))
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

# Every file whose hash is recorded in identity.json and compared by the gates. release-sprt-audit.py is in the list so
# an approved audit is bound to the exact audit program that produced it. tools/tests/test_release_sprt_audit.py pins this
# list to SCRIPT_NAMES in release-sprt-audit.py.
function Get-ReleaseSprtScriptNames {
    return @('release-strength-sprt.ps1', 'release-strength-sprt-common.ps1', 'release-strength-sprt-validation.ps1', 'phase21-stage6-common.ps1',
        'phase21-stage6.ps1', 'sprt.ps1', 'release-sprt-analyze.py', 'release-sprt-openings.py', 'release-sprt-audit.py')
}

# The acceptance criteria an audit record must show as passed. Same ids, same order as CRITERIA in release-sprt-audit.py.
function Get-ReleaseSprtAuditCriteria {
    return @('evidence-manifest', 'attempt-completed', 'frozen-identities', 'script-identity', 'cutechess-arguments', 'host-and-tools',
        'games-complete', 'opening-pairs', 'results-reconcile', 'no-faults', 'smoke-isolation', 'runner-corroboration')
}

# PowerShell resolves a relative path against $PWD, but .NET file APIs (File.OpenRead, ZipFile.OpenRead) resolve it against the
# process directory, which a session can leave somewhere else. Passing -AssetDirectory ..\release-sprt-assets therefore hashed
# fine and then failed in Assert-Stage6Jar. The runner resolves the directory once, here, and uses only the full path after that.
# Written without the .NET Core path overloads so it behaves the same in Windows PowerShell 5.1.
function Resolve-ReleaseSprtDirectory([string]$Path, [string]$BaseDirectory) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'Pass -AssetDirectory with the two published JARs.' }
    if ($BaseDirectory -notmatch '^[A-Za-z]:[\\/]') { throw "The base directory must be a full drive path: $BaseDirectory" }
    if ($Path -match '^[A-Za-z]:[\\/]') { $full = [IO.Path]::GetFullPath($Path) }
    elseif ($Path -match '^([\\/]|[A-Za-z]:)') { throw "The asset directory must be a relative path or a full drive path, not UNC, drive-relative or rooted without a drive: $Path" }
    else { $full = [IO.Path]::GetFullPath([IO.Path]::Combine($BaseDirectory, $Path)) }
    if (-not (Test-Path -LiteralPath $full -PathType Container)) { throw "The asset directory does not exist: $full" }
    return $full
}

# One attempt at a time on this machine: a second concurrent run would change the host load the preregistration assumes.
# The OS marks a mutex abandoned when the process that owns it dies without releasing it. WaitOne then throws
# AbandonedMutexException but still hands the mutex to the caller, so a killed attempt does not block the next one; the
# lock records that it recovered so the attempt can say so. The name is a parameter only so tests never touch the real lock.
#
# The wrapper holds $Name, but the match itself runs in a child process that outlives a wrapper which is killed outright. The child
# holds $MatchName for as long as it lives, so an attempt that finds it taken refuses to start: the main lock alone would have been
# released with the dead wrapper while Cute Chess was still playing. The child passes '' as $MatchName because it is the holder.
# What this does not cover: a Cute Chess process that outlives its killed child.
function Enter-ReleaseSprtLock([string]$Name = 'Global\VexReleaseSprt', [string]$MatchName = 'Global\VexReleaseSprtMatch') {
    $mutex = New-Object Threading.Mutex($false, $Name)
    $recovered = $false
    try { $acquired = $mutex.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $acquired = $true; $recovered = $true }
    catch { $mutex.Dispose(); throw }
    if (-not $acquired) { $mutex.Dispose(); throw 'Another release SPRT attempt is running on this machine.' }
    $lock = [pscustomobject]@{ Mutex = $mutex; Recovered = $recovered }
    if ($MatchName) {
        $probe = New-Object Threading.Mutex($false, $MatchName)
        $free = $false
        try {
            try { $free = $probe.WaitOne(0) } catch [Threading.AbandonedMutexException] { $free = $true }
            if ($free) { $probe.ReleaseMutex() }
        }
        finally { $probe.Dispose() }
        if (-not $free) { Exit-ReleaseSprtLock $lock; throw 'A match started by an earlier attempt is still running on this machine.' }
    }
    return $lock
}

function Exit-ReleaseSprtLock($Lock) {
    if ($null -eq $Lock) { return }
    try { $Lock.Mutex.ReleaseMutex() } finally { $Lock.Mutex.Dispose() }
}

# ConvertFrom-Json turns an ISO timestamp into a [datetime] in PowerShell 7 and leaves a string in 5.1; accept both.
# A timestamp with no zone is refused: ToUniversalTime would read it in this machine's local zone and shift the 24-hour check.
function ConvertTo-ReleaseSprtUtc($Value) {
    if ($Value -is [datetime]) {
        if ($Value.Kind -eq [DateTimeKind]::Unspecified) { throw "Timestamp has no time zone: $Value" }
        return $Value.ToUniversalTime()
    }
    if ([string]$Value -cnotmatch '^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?(Z|[+-]\d\d:\d\d)$') { throw "Timestamp is not ISO 8601 with a time zone: $Value" }
    return [datetime]::Parse([string]$Value, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime()
}

function Read-ReleaseSprtJson([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$(Split-Path -Leaf $Path) is missing" }
    return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json)
}

# Reasons the directory's SHA256SUMS cannot vouch for its contents. Empty means the manifest is non-empty, names only plain files
# in this directory, lists each once, every listed file hashes to its entry, and no file or subdirectory is left unlisted.
function Get-ReleaseSprtEvidenceProblems([string]$Directory) {
    $problems = New-Object Collections.Generic.List[string]
    $sums = Join-Path $Directory 'SHA256SUMS'
    if (-not (Test-Path -LiteralPath $sums -PathType Leaf)) { return @('SHA256SUMS is missing') }
    $lines = @(Get-Content -LiteralPath $sums | Where-Object { $_ -ne '' })
    if ($lines.Count -eq 0) { $problems.Add('SHA256SUMS is empty') }
    $listed = New-Object Collections.Generic.List[string]
    foreach ($line in $lines) {
        $parts = $line -split '  ', 2
        if ($parts.Count -ne 2 -or $parts[0] -cnotmatch '^[0-9a-f]{64}$' -or $parts[1] -in @('', '.', '..') -or [IO.Path]::GetFileName($parts[1]) -cne $parts[1]) {
            $problems.Add("malformed SHA256SUMS line: $line"); continue
        }
        if ($listed.Contains($parts[1])) { $problems.Add("duplicate SHA256SUMS entry: $($parts[1])") }
        $listed.Add($parts[1])
        $file = Join-Path $Directory $parts[1]
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { $problems.Add("listed file is missing: $($parts[1])") }
        elseif ((Get-Item -LiteralPath $file -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { $problems.Add("listed file is a link, not a plain file: $($parts[1])") }
        elseif ((Get-Stage6Hash $file) -cne $parts[0]) { $problems.Add("hash mismatch: $($parts[1])") }
    }
    foreach ($entry in @(Get-ChildItem -LiteralPath $Directory -Force)) {
        if ($entry.Name -ceq 'SHA256SUMS') { continue }
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { $problems.Add("link in the evidence directory: $($entry.Name)") }
        elseif ($entry.PSIsContainer) { $problems.Add("unverifiable subdirectory: $($entry.Name)") }
        elseif (-not $listed.Contains($entry.Name)) { $problems.Add("file not in SHA256SUMS: $($entry.Name)") }
    }
    return $problems.ToArray()
}

# Keys of the current config whose recorded value differs. The recorded config is the whole frozen experiment (JAR and opening
# hashes, preregistration hash, SPRT bounds, time control, concurrency, options, game cap), so any change shows up here.
function Get-ReleaseSprtConfigMismatches($Recorded, $Config) {
    $bad = @()
    foreach ($key in $Config.Keys) {
        $property = $Recorded.psobject.Properties[$key]
        $want = $Config[$key]
        if ($null -eq $property) { $bad += $key; continue }
        $same = if ($want -is [array]) { (@($property.Value) -join "`0") -ceq (@($want) -join "`0") } else { [string]$property.Value -ceq [string]$want }
        if (-not $same) { $bad += $key }
    }
    return $bad
}

# The attempt's archived copies must still be the published JARs, the frozen opening list for its kind and the frozen
# preregistration. The names are the ones release-strength-sprt.ps1 gives its archive copies.
function Get-ReleaseSprtArchivedInputProblems([string]$Directory, $Config, [string]$OpeningRelativePath, [string]$OpeningSha256) {
    $problems = @()
    foreach ($pair in @(@('candidate-v0.6.0.jar', $Config.CandidateSha256), @('baseline-v0.5.7.jar', $Config.BaselineSha256),
                        @((Split-Path -Leaf $OpeningRelativePath), $OpeningSha256), @('preregistration.md', $Config.PreregistrationSha256))) {
        $file = Join-Path $Directory $pair[0]
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { $problems += "archived $($pair[0]) is missing" }
        elseif ((Get-Stage6Hash $file) -cne $pair[1]) { $problems += "archived $($pair[0]) differs from the frozen identity" }
    }
    return $problems
}

# Identity checks shared by preflight and smoke evidence: same commit, same script hashes, same frozen experiment.
function Get-ReleaseSprtIdentityProblems($Identity, $Start, [string]$Head, $ScriptHashes, $Config) {
    $problems = @()
    if ($Identity.InvokingHead -cne $Head -or $Start.InvokingHead -cne $Head) { $problems += 'recorded from a different commit' }
    $changed = @($ScriptHashes.Keys | Where-Object { $null -eq $Identity.ScriptSha256.psobject.Properties[$_] -or $Identity.ScriptSha256.$_ -cne $ScriptHashes[$_] })
    if ($changed.Count) { $problems += "script hashes differ: $($changed -join ', ')" }
    $mismatches = @(Get-ReleaseSprtConfigMismatches $Identity.Config $Config)
    if ($mismatches.Count) { $problems += "frozen configuration differs: $($mismatches -join ', ')" }
    $assets = @{}; foreach ($asset in @($Identity.Assets)) { $assets[[string]$asset.Arm] = [string]$asset.Sha256 }
    if ($assets['candidate'] -cne $Config.CandidateSha256 -or $assets['baseline'] -cne $Config.BaselineSha256) { $problems += 'recorded JAR identities differ from the published ones' }
    return $problems
}

function Get-ReleaseSprtPreflightProblems([string]$Directory, [string]$Head, $ScriptHashes, [datetime]$Now, $Config) {
    $problems = New-Object Collections.Generic.List[string]
    try {
        foreach ($p in @(Get-ReleaseSprtEvidenceProblems $Directory)) { $problems.Add($p) }
        if ($problems.Count) { return $problems.ToArray() }
        $start = Read-ReleaseSprtJson (Join-Path $Directory 'attempt-start.json'); $end = Read-ReleaseSprtJson (Join-Path $Directory 'attempt-end.json')
        $summary = Read-ReleaseSprtJson (Join-Path $Directory 'summary.json'); $identity = Read-ReleaseSprtJson (Join-Path $Directory 'identity.json')
        if ($start.Kind -cne 'preflight' -or $end.Kind -cne 'preflight' -or $identity.Kind -cne 'preflight') { $problems.Add('not a preflight attempt') }
        $leaf = Split-Path -Leaf $Directory
        if ($start.RunId -cne $leaf -or $end.RunId -cne $leaf) { $problems.Add('attempt-start and attempt-end belong to a different attempt than this directory') }
        if ($summary.Verdict -cne 'PREFLIGHT_PASSED' -or $summary.GamesStarted -ne 0) { $problems.Add("verdict is $($summary.Verdict), not PREFLIGHT_PASSED with no games") }
        if ($end.Failure -cne '' -or $end.MatchStarted -ne $false) { $problems.Add('attempt-end records a failure or a started match') }
        $started = ConvertTo-ReleaseSprtUtc $start.StartedUtc; $ended = ConvertTo-ReleaseSprtUtc $end.EndedUtc
        $limit = [double]$Config.MaxPreflightAgeHours
        if ($ended -lt $started) { $problems.Add('ended before it started') }
        if ($started -gt $Now.AddMinutes(5) -or $ended -gt $Now.AddMinutes(5)) { $problems.Add('timestamps are in the future') }
        elseif (($Now - $started).TotalHours -gt $limit -or ($Now - $ended).TotalHours -gt $limit) { $problems.Add(('stale: started {0:N1} h and completed {1:N1} h ago, limit {2} h' -f ($Now - $started).TotalHours, ($Now - $ended).TotalHours, $limit)) }
        foreach ($p in @(Get-ReleaseSprtIdentityProblems $identity $start $Head $ScriptHashes $Config)) { $problems.Add($p) }
        foreach ($p in @(Get-ReleaseSprtArchivedInputProblems $Directory $Config $Config.OpeningRelativePath $Config.OpeningSha256)) { $problems.Add($p) }
    }
    catch { $problems.Add("could not be evaluated: $($_.Exception.Message)") }
    return $problems.ToArray()
}

# The reasons the newest candidates were turned away, for the refusal message. Empty when there was nothing to evaluate.
function Format-ReleaseSprtRejections($Rejections) {
    if ($null -eq $Rejections -or $Rejections.Count -eq 0) { return ' No candidate attempt directory exists.' }
    return " Rejected ($($Rejections.Count)): $(@($Rejections | Select-Object -First 3) -join ' | ')"
}

# The gate for -SmokeNative and -ExecuteNative: a native preflight from this exact commit and scripts, with the same published
# JARs, opening corpus and preregistration, started and completed within MaxPreflightAgeHours, whose evidence still verifies.
# Returns the directory name, or $null. $Rejections, when given, receives one line per rejected candidate.
function Find-ReleaseSprtPassedPreflight([string]$ResultsRoot, [string]$Head, $ScriptHashes, [datetime]$Now = [datetime]::UtcNow, $Config = $null, $Rejections = $null) {
    if ($null -eq $Config) { $Config = Get-ReleaseSprtConfig }
    if (-not (Test-Path -LiteralPath $ResultsRoot)) { return $null }
    foreach ($dir in @(Get-ChildItem -LiteralPath $ResultsRoot -Directory -Filter 'preflight-*' | Sort-Object Name -Descending)) {
        $problems = @(Get-ReleaseSprtPreflightProblems $dir.FullName $Head $ScriptHashes $Now $Config)
        if ($problems.Count -eq 0) { return $dir.Name }
        if ($null -ne $Rejections) { $Rejections.Add("$($dir.Name): $($problems -join '; ')") }
    }
    return $null
}

function Get-ReleaseSprtSmokeProblems([string]$Directory, [string]$Head, $ScriptHashes, $Config) {
    $problems = New-Object Collections.Generic.List[string]
    try {
        foreach ($p in @(Get-ReleaseSprtEvidenceProblems $Directory)) { $problems.Add($p) }
        if ($problems.Count) { return $problems.ToArray() }
        $start = Read-ReleaseSprtJson (Join-Path $Directory 'attempt-start.json'); $end = Read-ReleaseSprtJson (Join-Path $Directory 'attempt-end.json')
        $identity = Read-ReleaseSprtJson (Join-Path $Directory 'identity.json')
        if ($start.Kind -cne 'smoke' -or $end.Kind -cne 'smoke' -or $identity.Kind -cne 'smoke') { $problems.Add('not a smoke attempt') }
        $leaf = Split-Path -Leaf $Directory
        if ($start.RunId -cne $leaf -or $end.RunId -cne $leaf) { $problems.Add('attempt-start and attempt-end belong to a different attempt than this directory') }
        if ($end.MatchStarted -ne $true -or $end.MatchCompleted -ne $true -or $end.ExitCode -ne 0 -or $end.Failure -cne '') { $problems.Add('attempt-end does not show a completed, clean match') }
        if ((ConvertTo-ReleaseSprtUtc $end.EndedUtc) -lt (ConvertTo-ReleaseSprtUtc $start.StartedUtc)) { $problems.Add('ended before it started') }
        foreach ($p in @(Get-ReleaseSprtIdentityProblems $identity $start $Head $ScriptHashes $Config)) { $problems.Add($p) }
        foreach ($p in @(Get-ReleaseSprtArchivedInputProblems $Directory $Config $Config.SmokeOpeningRelativePath $Config.SmokeOpeningSha256)) { $problems.Add($p) }
    }
    catch { $problems.Add("could not be evaluated: $($_.Exception.Message)") }
    return $problems.ToArray()
}

function Get-ReleaseSprtAuditProblems([string]$Directory, [string]$ResultsRoot, [string]$Head, $ScriptHashes, [datetime]$Now, $Config) {
    $problems = New-Object Collections.Generic.List[string]
    try {
        foreach ($p in @(Get-ReleaseSprtEvidenceProblems $Directory)) { $problems.Add($p) }
        if ($problems.Count) { return $problems.ToArray() }
        $record = Read-ReleaseSprtJson (Join-Path $Directory 'smoke-audit.json')
        if ($record.Schema -ne 1) { $problems.Add("unknown audit schema $($record.Schema)") }
        if ($record.Verdict -cne 'AUDIT_APPROVED') { $problems.Add("audit verdict is $($record.Verdict)") }
        if ($record.AuditTool.Name -cne 'release-sprt-audit.py' -or $null -eq $ScriptHashes['release-sprt-audit.py'] -or $record.AuditTool.Sha256 -cne $ScriptHashes['release-sprt-audit.py']) { $problems.Add('audited by a different audit program than the one in this checkout') }
        if ($null -eq $ScriptHashes['release-sprt-analyze.py'] -or $null -eq $record.AuditTool.psobject.Properties['AnalyzerSha256'] -or $record.AuditTool.AnalyzerSha256 -cne $ScriptHashes['release-sprt-analyze.py']) { $problems.Add('audited with a different analyzer than the one in this checkout') }
        if ($null -eq $record.Smoke.psobject.Properties['LocationVerified'] -or $record.Smoke.LocationVerified -isnot [bool] -or $record.Smoke.LocationVerified -ne $true) { $problems.Add('the audit could not confirm that the recorded paths are the audited directory (run the audit from the native Windows checkout)') }
        $byId = @{}; foreach ($c in @($record.Criteria)) { $byId[[string]$c.Id] = $c }
        foreach ($id in @(Get-ReleaseSprtAuditCriteria)) {
            if (-not $byId.ContainsKey($id)) { $problems.Add("criterion not audited: $id") }
            elseif ($byId[$id].Passed -isnot [bool] -or $byId[$id].Passed -ne $true) { $problems.Add("criterion failed: $id") }
        }
        if (@($record.Criteria).Count -ne @(Get-ReleaseSprtAuditCriteria).Count) { $problems.Add('unexpected criteria in the record') }
        $runId = [string]$record.Smoke.RunId
        if ($runId -cnotmatch '^smoke-\d{8}T\d{9}Z-[0-9a-f]{8}$') { $problems.Add("malformed smoke run id: $runId"); return $problems.ToArray() }
        $smoke = Join-Path $ResultsRoot $runId
        if (-not (Test-Path -LiteralPath $smoke -PathType Container)) { $problems.Add("audited smoke attempt is missing: $runId"); return $problems.ToArray() }
        if ($record.Smoke.InvokingHead -cne $Head) { $problems.Add('the audited smoke ran on a different commit') }
        $changed = @($ScriptHashes.Keys | Where-Object { $null -eq $record.Smoke.ScriptSha256.psobject.Properties[$_] -or $record.Smoke.ScriptSha256.$_ -cne $ScriptHashes[$_] })
        if ($changed.Count) { $problems.Add("the audited smoke ran on different scripts: $($changed -join ', ')") }
        if ((Get-Stage6Hash (Join-Path $smoke 'SHA256SUMS')) -cne [string]$record.Smoke.ManifestSha256) { $problems.Add('the smoke evidence manifest is not the one that was audited') }
        foreach ($p in @(Get-ReleaseSprtSmokeProblems $smoke $Head $ScriptHashes $Config)) { $problems.Add("smoke $runId`: $p") }
        $audited = ConvertTo-ReleaseSprtUtc $record.AuditedUtc
        if ($audited -lt (ConvertTo-ReleaseSprtUtc $record.Smoke.EndedUtc) -or $audited -gt $Now.AddMinutes(5)) { $problems.Add('audit timestamp is before the smoke ended or in the future') }
    }
    catch { $problems.Add("could not be evaluated: $($_.Exception.Message)") }
    return $problems.ToArray()
}

# The additional gate for -ExecuteNative. SMOKE_PASSED in a smoke summary is produced by the same runner that played the games,
# so it authorizes nothing by itself. Production needs an audit record written by tools/release-sprt-audit.py, an independent
# program that re-derives the verdict from the archived files. This checks the record's checksums, that it names this commit,
# these scripts and this audit program, that every acceptance criterion is present and passed, and that the smoke attempt it
# names still has the exact evidence manifest the audit approved and still verifies. Returns the audit and smoke names, or $null.
function Find-ReleaseSprtApprovedSmoke([string]$ResultsRoot, [string]$Head, $ScriptHashes, [datetime]$Now = [datetime]::UtcNow, $Config = $null, $Rejections = $null) {
    if ($null -eq $Config) { $Config = Get-ReleaseSprtConfig }
    if (-not (Test-Path -LiteralPath $ResultsRoot)) { return $null }
    foreach ($dir in @(Get-ChildItem -LiteralPath $ResultsRoot -Directory -Filter 'audit-*' | Sort-Object Name -Descending)) {
        $problems = @(Get-ReleaseSprtAuditProblems $dir.FullName $ResultsRoot $Head $ScriptHashes $Now $Config)
        if ($problems.Count -eq 0) {
            $record = Read-ReleaseSprtJson (Join-Path $dir.FullName 'smoke-audit.json')
            return [pscustomobject]@{ Audit = $dir.Name; Smoke = [string]$record.Smoke.RunId; ManifestSha256 = [string]$record.Smoke.ManifestSha256 }
        }
        if ($null -ne $Rejections) { $Rejections.Add("$($dir.Name): $($problems -join '; ')") }
    }
    return $null
}
