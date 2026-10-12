# Pure configuration and saved-evidence helpers. This file never starts a process.
Set-StrictMode -Version Latest

function Get-Stage6Config {
    return [ordered]@{
        ControlCommit = 'd3a56ffadf0d9151a2b19fba4902a734a99bff96'
        CandidateCommit = 'e90d3d4f90b244c46e8bbf75c33222bba7d58003'
        OpeningSha256 = '2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347'
        Elo0 = 0; Elo1 = 10; Alpha = 0.05; Beta = 0.05; BonferroniM = 1
        MinGames = 0; MaxGames = 20000; TC = '5+0.05'; Concurrency = 6; EngineThreads = 1
        Options = @('Hash=16', 'PawnHashSize=1', 'EvalType=Classical', 'MultiPV=1', 'Contempt=0',
                    'OwnBook=false', 'SyzygyOnline=false', 'SyzygyPath=')
    }
}

function Save-Stage6Json($Value, [string]$Path) {
    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-Stage6Hash([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-Stage6HostFacts($Facts) {
    if ($Facts.Platform -ne 'Win32NT' -or $Facts.ComputerName -ine 'RENEGADE' -or
        $Facts.CpuName -notmatch '^AMD Ryzen 7 7700X(?:\s|$)') { throw 'Wrong native Stage 6 host.' }
    if ($Facts.PowerPlanRaw -notmatch '381b4222-f694-41f0-9685-ff5bb260df2e' -or
        $Facts.AffinityMask -ne $Facts.FullAffinityMask) { throw 'Stage 6 requires Balanced plan and full affinity.' }
    if ($Facts.JavaVersion -ne '21.0.10' -or $Facts.JavaRuntime -ne '21.0.10+7-LTS' -or
        $Facts.JavaVendor -notmatch 'Azul') { throw 'Wrong Stage 6 JDK.' }
    if ($Facts.MavenVersion -ne '3.9.16' -or $Facts.MavenJavaHome -ine $Facts.JavaHome) { throw 'Wrong Maven version or Java runtime.' }
    if ($Facts.CutechessVersion -ne '1.5.1') { throw 'Stage 6 requires cutechess-cli 1.5.1.' }
}

function Get-Stage6Parameters([string]$Directory, [string]$Openings) {
    $config = Get-Stage6Config
    $parameters = @{
        New = Join-Path $Directory 'candidate.jar'; Old = Join-Path $Directory 'control.jar'
        NewOptions = @($config.Options); OldOptions = @($config.Options)
        OpeningsFile = $Openings; Tag = 'phase21-stage6'
        LogPath = Join-Path $Directory 'match.log'; PgnPath = Join-Path $Directory 'match.pgn'
    }
    foreach ($key in @('Elo0','Elo1','Alpha','Beta','BonferroniM','MinGames','MaxGames','TC','Concurrency','EngineThreads')) {
        $parameters[$key] = $config[$key]
    }
    return $parameters
}

function Assert-Stage6Parameters($Parameters) {
    $config = Get-Stage6Config
    foreach ($key in @('Elo0','Elo1','Alpha','Beta','BonferroniM','MinGames','MaxGames','TC','Concurrency','EngineThreads')) {
        if (-not $Parameters.ContainsKey($key) -or $Parameters[$key] -cne $config[$key]) {
            throw "Stage 6 parameter differs from approval: $key"
        }
    }
    foreach ($key in @('NewOptions','OldOptions')) {
        if (($Parameters[$key] -join "`0") -cne ($config.Options -join "`0")) {
            throw "Stage 6 options differ from approval: $key"
        }
    }
    $paths = @()
    foreach ($key in @('New','Old','OpeningsFile','LogPath','PgnPath')) {
        if (-not $Parameters.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($Parameters[$key])) { throw "Missing Stage 6 path: $key" }
        $paths += [IO.Path]::GetFullPath($Parameters[$key])
    }
    if (@($paths | Select-Object -Unique).Count -ne 5) { throw 'Stage 6 inputs and output paths must be distinct.' }
}

function Assert-Stage6Arguments($Manifest, $Parameters, [string]$Java, [string]$Cutechess) {
    Assert-Stage6Parameters $Parameters
    $expected = @()
    foreach ($arm in @(@{ Name='NEW'; Jar=$Parameters.New; Options=$Parameters.NewOptions },
                        @{ Name='OLD'; Jar=$Parameters.Old; Options=$Parameters.OldOptions })) {
        $expected += @('-engine', "name=$($arm.Name)", "cmd=$Java", 'arg=--add-modules',
            'arg=jdk.incubator.vector', 'arg=-jar', "arg=$($arm.Jar)", 'proto=uci', 'option.Threads=1')
        $expected += @($arm.Options | ForEach-Object { "option.$_" })
    }
    $expected += @('-each','tc=5+0.05','-games','20000','-repeat','-recover',
        '-resign','movecount=5','score=400','-draw','movenumber=40','movecount=8','score=10',
        '-sprt','elo0=0','elo1=10','alpha=0.05','beta=0.05','-concurrency','6','-ratinginterval','10',
        '-pgnout',$Parameters.PgnPath,'-openings',"file=$($Parameters.OpeningsFile)",'format=epd','order=random','plies=4')
    if ($Manifest.Executable -cne $Cutechess -or
        ($Manifest.Arguments -join "`0") -cne ($expected -join "`0")) {
        throw 'Actual executable/arguments do not match the approved Phase 21 match.'
    }
}

function Test-Stage6FaultLine([string]$Line) {
    return $Line -match '(?i)\b(crash(?:ed)?|disconnect(?:ed)?|forfeit|timeout|timed? out|illegal move|connection stalls?|stalled connection|wins on time|loses on time|lost on time|cannot start|could not start|failed to start|unknown option|unrecognized option|unsupported option|invalid option|does not support option|doesn''t support option)\b|^\s*(Error:|Exception in thread)'
}

function Get-Stage6Verdict([string]$Decision, [int]$Scored, [bool]$Finished,
                          [bool]$Fault, [int]$ExitCode, [bool]$Interrupted) {
    if ($Fault) { return 'SETUP_PROTOCOL_FAULT' }
    if ($Interrupted) { return 'INTERRUPTED' }
    if ($ExitCode -ne 0) { return 'SETUP_PROTOCOL_FAULT' }
    if (-not $Finished -or $Scored -eq 0) { return 'INCOMPLETE' }
    if ($Decision -eq 'H1') { return 'H1_ACCEPTED' }
    if ($Decision -eq 'H0') { return 'H0_ACCEPTED' }
    if ($Scored -eq 20000) { return 'INCONCLUSIVE' }
    return 'INCOMPLETE'
}

function Read-Stage6Results([string]$Log, [string]$Pgn, [int]$ExitCode = -1, [bool]$Interrupted = $false) {
    $issues = New-Object System.Collections.Generic.List[string]
    $faults = @($Log -split '\r?\n' | Where-Object { Test-Stage6FaultLine $_ })
    $scoreRows = [regex]::Matches($Log, '(?m)^Score of NEW vs OLD:\s*(\d+)\s*-\s*(\d+)\s*-\s*(\d+)\s+\[[^\]]+\]\s+(\d+)\s*$')
    $logWins = 0; $logLosses = 0; $logDraws = 0
    if ($scoreRows.Count -gt 0) {
        $last = $scoreRows[$scoreRows.Count - 1]
        $logWins = [int]$last.Groups[1].Value; $logLosses = [int]$last.Groups[2].Value; $logDraws = [int]$last.Groups[3].Value
        if ($logWins + $logLosses + $logDraws -ne [int]$last.Groups[4].Value) { $issues.Add('Log score total is inconsistent.') }
    }
    $llrRows = [regex]::Matches($Log, '(?m)^SPRT:\s+llr\s+(-?[\d.]+).*?lbound\s+(-?[\d.]+),\s+ubound\s+(-?[\d.]+)([^\r\n]*)')
    $llr = $null; $lower = $null; $upper = $null; $decision = ''
    if ($llrRows.Count -gt 0) {
        $last = $llrRows[$llrRows.Count - 1]
        $llr = [double]::Parse($last.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
        $lower = [double]::Parse($last.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture)
        $upper = [double]::Parse($last.Groups[3].Value, [Globalization.CultureInfo]::InvariantCulture)
        if ([math]::Abs($lower + [math]::Log(19)) -gt 0.01 -or [math]::Abs($upper - [math]::Log(19)) -gt 0.01) { $issues.Add('Unexpected LLR boundaries.') }
        if ($last.Groups[4].Value -match 'H([01]) was accepted') { $decision = 'H' + $Matches[1] }
        if (($decision -eq 'H1' -and $llr -lt $upper - 0.01) -or ($decision -eq 'H0' -and $llr -gt $lower + 0.01)) { $issues.Add('Verdict disagrees with LLR.') }
        if (-not $decision -and ($llr -gt $upper + 0.01 -or $llr -lt $lower - 0.01)) { $issues.Add('LLR crossed a boundary without an accepted decision.') }
    }
    $events = [regex]::Matches($Log, '(?m)^Finished game (\d+) \((NEW|OLD) vs (NEW|OLD)\): (1-0|0-1|1/2-1/2|\*)')
    $eventIds = @($events | ForEach-Object { $_.Groups[1].Value })
    if (@($eventIds | Select-Object -Unique).Count -ne $eventIds.Count) { $issues.Add('Duplicate finished-game IDs.') }
    $logScored = @($events | Where-Object { $_.Groups[4].Value -ne '*' }).Count
    $logCancelled = $events.Count - $logScored
    if ($logScored -ne $logWins + $logLosses + $logDraws) { $issues.Add('Log events disagree with score.') }
    $eventWins=0; $eventLosses=0; $eventDraws=0
    foreach ($event in $events) {
        $white=$event.Groups[2].Value; $black=$event.Groups[3].Value; $outcome=$event.Groups[4].Value
        if ($white -eq $black) { $issues.Add('Log event has identical engines.') }
        if ($outcome -eq '*') { continue }
        if ($outcome -eq '1/2-1/2') { $eventDraws++ }
        elseif (($white -eq 'NEW' -and $outcome -eq '1-0') -or ($black -eq 'NEW' -and $outcome -eq '0-1')) { $eventWins++ }
        else { $eventLosses++ }
    }
    if ($eventWins -ne $logWins -or $eventLosses -ne $logLosses -or $eventDraws -ne $logDraws) { $issues.Add('Log event colors/results disagree with score.') }

    $colors = @{ White = @{ Wins=0; Losses=0; Draws=0 }; Black = @{ Wins=0; Losses=0; Draws=0 } }
    $pairs = @{}; $wins = 0; $losses = 0; $draws = 0; $cancelled = 0; $partial = 0
    $games = [regex]::Matches($Pgn, '(?ms)^\[Event\s+"[^"\r\n]*"\]\r?\n.*?(?=^\[Event\s+|\z)')
    if ([regex]::Matches($Pgn,'(?m)^\[Event\s+').Count -ne $games.Count) { $issues.Add('PGN contains an unparsed game header.') }
    foreach ($game in $games) {
        $tags = @{}
        foreach ($tag in [regex]::Matches($game.Value, '(?m)^\[(\w+) "((?:\\.|[^"\\])*)"\]\s*$')) { $tags[$tag.Groups[1].Value] = $tag.Groups[2].Value }
        foreach ($key in @('White','Black','Result','FEN','TimeControl')) { if (-not $tags.ContainsKey($key)) { $issues.Add("PGN missing $key.") } }
        if (@('White','Black','Result','FEN','TimeControl') | Where-Object { -not $tags.ContainsKey($_) }) { $partial++; continue }
        if (($tags.White -ne 'NEW' -or $tags.Black -ne 'OLD') -and ($tags.White -ne 'OLD' -or $tags.Black -ne 'NEW')) { $issues.Add('PGN engine names are inconsistent.'); continue }
        if ($tags.TimeControl -ne '5+0.05') { $issues.Add('PGN time control differs from approval.') }
        $body = [regex]::Replace($game.Value, '(?m)^\[.*\]\s*$', '')
        $body = [regex]::Replace($body, '(?s)\{.*?\}', '')
        $ending = [regex]::Match($body, '(?:^|\s)(1-0|0-1|1/2-1/2|\*)\s*\z')
        if (-not $ending.Success) { $partial++; continue }
        if ($ending.Groups[1].Value -ne $tags.Result) { $issues.Add('PGN result tag disagrees with movetext.'); continue }
        $color = if ($tags.White -eq 'NEW') { 'White' } else { 'Black' }
        if (-not $pairs.ContainsKey($tags.FEN)) {
            $pairs[$tags.FEN] = [pscustomobject]@{ FEN=$tags.FEN; NewWhite=0; NewBlack=0; CancelledWhite=0; CancelledBlack=0 }
        }
        $pair = $pairs[$tags.FEN]
        if ($tags.Result -eq '*') {
            $cancelled++
            if ($color -eq 'White') { $pair.CancelledWhite++ } else { $pair.CancelledBlack++ }
            continue
        }
        if ($color -eq 'White') { $pair.NewWhite++ } else { $pair.NewBlack++ }
        if ($tags.Result -eq '1/2-1/2') { $draws++; $colors[$color].Draws++ }
        elseif (($tags.Result -eq '1-0' -and $color -eq 'White') -or ($tags.Result -eq '0-1' -and $color -eq 'Black')) { $wins++; $colors[$color].Wins++ }
        else { $losses++; $colors[$color].Losses++ }
        if ($game.Value -match '(?i)\[(?:Termination|ResultDescription) "[^"\r\n]*(?:time forfeit|timeout|illegal|crash|disconnect|forfeit)') { $faults += $Matches[0] }
    }
    $scored = $wins + $losses + $draws
    if ($wins -ne $logWins -or $losses -ne $logLosses -or $draws -ne $logDraws -or $cancelled -ne $logCancelled) { $issues.Add('PGN/log W/L/D or cancellation mismatch.') }
    if ($scored -gt 20000) { $issues.Add('Scored-game cap exceeded.') }
    if ($partial -gt 0) { $issues.Add('PGN contains incomplete game records.') }
    if ($scored -gt 0 -and $llrRows.Count -eq 0) { $issues.Add('No LLR record.') }
    if ($Log -match 'H0 was accepted' -and $Log -match 'H1 was accepted') { $issues.Add('Conflicting SPRT decisions.') }
    $finished = $Log -match '(?m)^Finished match\s*$'
    $fault = $faults.Count -gt 0 -or $issues.Count -gt 0
    $verdict = Get-Stage6Verdict $decision $scored $finished $fault $ExitCode $Interrupted
    $pairRows = @($pairs.Values | Sort-Object FEN)
    return [pscustomobject]@{
        Verdict=$verdict; EligibleForPromotion=($verdict -eq 'H1_ACCEPTED')
        Wins=$wins; Losses=$losses; Draws=$draws; Scored=$scored; Cancelled=$cancelled; PartialPgnRecords=$partial
        Llr=$llr; LowerBound=$lower; UpperBound=$upper; Decision=$decision; FinishedMatch=$finished; ExitCode=$ExitCode
        CandidateByColor=$colors; CompletedOpeningPairs=($pairRows | ForEach-Object { [math]::Min($_.NewWhite,$_.NewBlack) } | Measure-Object -Sum).Sum
        UnpairedScoredGames=($pairRows | ForEach-Object { [math]::Abs($_.NewWhite-$_.NewBlack) } | Measure-Object -Sum).Sum
        FaultLines=@($faults); AuditIssues=@($issues.ToArray()); PairAudit=$pairRows
    }
}
