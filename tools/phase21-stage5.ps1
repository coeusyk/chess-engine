<#
.SYNOPSIS
    Phase 21 Stage 5 native-Windows throughput qualification.

.DESCRIPTION
    Builds the frozen control and Stage 2 candidate in detached native Git
    worktrees, verifies depth-13 node totals, then runs the frozen warm-up and
    interleaved seven-run timing protocol. This script intentionally performs
    no DEBUG, JFR, clock-bound, game, or SPRT work.

.PARAMETER ValidateOnly
    Exercise schedule, parser, decision, cleanup, and command-failure handling
    with synthetic fixtures. Does not probe the benchmark host, create Git
    worktrees, build, or run the engine. Safe to invoke from WSL.

.EXAMPLE
    .\tools\phase21-stage5.ps1
#>
[CmdletBinding()]
param(
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSNativeCommandUseErrorActionPreference = $false

$script:ControlCommit = 'd3a56ffadf0d9151a2b19fba4902a734a99bff96'
$script:CandidateCommit = 'e90d3d4f90b244c46e8bbf75c33222bba7d58003'
$script:ControlNodes = [long]24780049
$script:CandidateNodes = [long]21713284
$script:BenchRunnerSha256 = '882f5edc5159dba94bb6faefbb456f1a12e77f2798753a6a82d58755c9b1361f'
$script:NpsFloor = [long]301116
$script:JvmArguments = @(
    '-Xms512m',
    '-Xmx512m',
    '-XX:+UseG1GC',
    '--add-modules', 'jdk.incubator.vector'
)
$script:BuildArguments = @(
    '-B', '-pl', 'engine-core,engine-uci', '-am', 'package', '-DskipTests'
)

function New-Stage5Schedule {
    $items = New-Object System.Collections.Generic.List[object]
    $items.Add([pscustomobject]@{ Phase = 'warmup'; Arm = 'control'; Ordinal = 0; Measured = $false })
    $items.Add([pscustomobject]@{ Phase = 'warmup'; Arm = 'candidate'; Ordinal = 0; Measured = $false })
    for ($i = 1; $i -le 7; $i++) {
        $items.Add([pscustomobject]@{ Phase = 'measured'; Arm = 'control'; Ordinal = $i; Measured = $true })
        $items.Add([pscustomobject]@{ Phase = 'measured'; Arm = 'candidate'; Ordinal = $i; Measured = $true })
    }
    return $items.ToArray()
}

function Get-Stage5Median([object[]]$Values) {
    if ($Values.Count -ne 7) { throw "Expected exactly seven values; got $($Values.Count)." }
    $sorted = @($Values | ForEach-Object { [double]$_ } | Sort-Object)
    return [double]$sorted[3]
}

function Assert-Stage5NodeCount([long]$Nodes, [string]$Arm, [string]$Label) {
    $expected = if ($Arm -eq 'control') { $script:ControlNodes } else { $script:CandidateNodes }
    if ($Nodes -ne $expected) { throw "$Label node mismatch: $Nodes, expected $expected." }
}

function Assert-Stage5ParserRejects([string]$Text, [string]$Label) {
    $rejected = $false
    try { $null = Parse-Stage5BenchOutput $Text } catch { $rejected = $true }
    if (-not $rejected) { throw "Raw-output parser accepted invalid fixture: $Label." }
}

function Remove-Stage5WorkRootIfEmpty([string]$WorkRoot) {
    if (-not (Test-Path -LiteralPath $WorkRoot -PathType Container)) { return $true }
    if (@(Get-ChildItem -LiteralPath $WorkRoot -Force).Count -gt 0) { return $false }
    Remove-Item -LiteralPath $WorkRoot -Force -ErrorAction Stop
    return $true
}

function Parse-Stage5BenchOutput([string]$Text) {
    $header = [regex]::Matches(
        $Text,
        '(?m)^Bench\s*:\s*depth\s+13\s+\|\s+hash\s+16 MB\s+\|\s+31 positions\s+\|\s+evaluator\s+Classical\s+\|\s+instrumentation\s+off\s*$'
    )
    if ($header.Count -ne 1) { throw 'Raw bench header must identify depth 13, Hash 16 MB, 31 positions, Classical, instrumentation off.' }

    function Read-One([string]$Pattern, [string]$Label) {
        $matches = [regex]::Matches($Text, $Pattern)
        if ($matches.Count -ne 1) { throw "Expected one '$Label' field in raw bench output; found $($matches.Count)." }
        return [long]$matches[0].Groups[1].Value
    }

    $nodes = Read-One '(?m)^Nodes searched:\s*(\d+)\s*$' 'Nodes searched'
    $elapsed = Read-One '(?m)^Time\s*:\s*(\d+)\s*ms\s*$' 'Time'
    $nps = Read-One '(?m)^NPS\s*:\s*(\d+)\s*$' 'NPS'
    if ($nodes -le 0 -or $elapsed -le 0 -or $nps -le 0) { throw 'Raw bench totals must be positive.' }
    return [pscustomobject]@{
        MainNodes = $nodes
        Qnodes = $null
        ElapsedMs = $elapsed
        Nps = $nps
        Instrumentation = 'off'
        Positions = 31
        Depth = 13
        HashMb = 16
        Evaluator = 'Classical'
    }
}

function Get-Stage5Decision([object[]]$ControlRuns, [object[]]$CandidateRuns) {
    if ($ControlRuns.Count -ne 7 -or $CandidateRuns.Count -ne 7) {
        throw 'The Stage 5 decision requires seven measured rows for each arm.'
    }
    $controlElapsed = @($ControlRuns | ForEach-Object { [double]$_.ElapsedMs })
    $candidateElapsed = @($CandidateRuns | ForEach-Object { [double]$_.ElapsedMs })
    $controlNps = @($ControlRuns | ForEach-Object { [double]$_.Nps })
    $candidateNps = @($CandidateRuns | ForEach-Object { [double]$_.Nps })

    $controlMedianElapsed = Get-Stage5Median $controlElapsed
    $candidateMedianElapsed = Get-Stage5Median $candidateElapsed
    $controlMedianNps = Get-Stage5Median $controlNps
    $candidateMedianNps = Get-Stage5Median $candidateNps
    $controlAggregateNps = [math]::Floor(
        ((($ControlRuns | Measure-Object -Property MainNodes -Sum).Sum) * 1000.0) /
        (($ControlRuns | Measure-Object -Property ElapsedMs -Sum).Sum)
    )
    $candidateAggregateNps = [math]::Floor(
        ((($CandidateRuns | Measure-Object -Property MainNodes -Sum).Sum) * 1000.0) /
        (($CandidateRuns | Measure-Object -Property ElapsedMs -Sum).Sum)
    )
    $controlMin = ($controlElapsed | Measure-Object -Minimum).Minimum
    $controlMax = ($controlElapsed | Measure-Object -Maximum).Maximum
    $candidateMin = ($candidateElapsed | Measure-Object -Minimum).Minimum
    $candidateMax = ($candidateElapsed | Measure-Object -Maximum).Maximum
    $elapsedRatio = $candidateMedianElapsed / $controlMedianElapsed
    $npsRatio = $candidateMedianNps / $controlMedianNps
    $improvementPct = 100.0 * ($controlMedianElapsed - $candidateMedianElapsed) / $controlMedianElapsed
    $elapsedPass = $candidateMedianElapsed -lt $controlMin
    $aggregateFloorPass = $candidateAggregateNps -ge $script:NpsFloor
    $medianFloorPass = $candidateMedianNps -ge $script:NpsFloor

    return [pscustomobject]@{
        ControlElapsedMinMs = $controlMin
        ControlElapsedMedianMs = $controlMedianElapsed
        ControlElapsedMaxMs = $controlMax
        CandidateElapsedMinMs = $candidateMin
        CandidateElapsedMedianMs = $candidateMedianElapsed
        CandidateElapsedMaxMs = $candidateMax
        CandidateControlMedianElapsedRatio = $elapsedRatio
        MedianElapsedImprovementPct = $improvementPct
        ControlMedianNps = $controlMedianNps
        CandidateMedianNps = $candidateMedianNps
        ControlAggregateNps = $controlAggregateNps
        CandidateAggregateNps = $candidateAggregateNps
        CandidateControlMedianNpsRatio = $npsRatio
        NpsFloor = $script:NpsFloor
        CandidateAggregateNpsFloorPass = $aggregateFloorPass
        CandidateMedianNpsFloorPass = $medianFloorPass
        CandidateMedianElapsedBelowControlMinimum = $elapsedPass
        Gate = if ($elapsedPass -and $aggregateFloorPass -and $medianFloorPass) { 'PASS' } else { 'STOP_BEFORE_STAGE_6' }
    }
}

function Invoke-Stage5ValidationOnly {
    if ($script:ControlNodes -ne 24780049 -or $script:CandidateNodes -ne 21713284 -or $script:NpsFloor -ne 301116) {
        throw 'Frozen node/NPS constants changed.'
    }
    $schedule = @(New-Stage5Schedule)
    if ($schedule.Count -ne 16) { throw "Schedule cardinality error: expected 16 invocations, got $($schedule.Count)." }
    if (($schedule | Where-Object { $_.Phase -eq 'warmup' }).Count -ne 2) { throw 'Schedule must contain one warm-up for each arm.' }
    if (($schedule | Where-Object { $_.Measured }).Count -ne 14) { throw 'Schedule must contain 14 measured invocations.' }
    $actualOrder = @($schedule | ForEach-Object { "$($_.Phase)-$($_.Arm)-$('{0:D2}' -f $_.Ordinal)" })
    $expected = @(
        'warmup-control-00', 'warmup-candidate-00',
        'measured-control-01', 'measured-candidate-01', 'measured-control-02', 'measured-candidate-02',
        'measured-control-03', 'measured-candidate-03', 'measured-control-04', 'measured-candidate-04',
        'measured-control-05', 'measured-candidate-05', 'measured-control-06', 'measured-candidate-06',
        'measured-control-07', 'measured-candidate-07'
    )
    if (($actualOrder -join '|') -ne ($expected -join '|')) { throw 'Frozen arm schedule mismatch.' }

    $fixture = @'
Bench   : depth 13 | hash 16 MB | 31 positions | evaluator Classical | instrumentation off
Nodes searched: 24780049
Time  : 10000 ms
NPS   : 2478004
'@
    $parsed = Parse-Stage5BenchOutput $fixture
    if ($parsed.MainNodes -ne $script:ControlNodes -or $parsed.Qnodes -ne $null -or $parsed.Instrumentation -ne 'off') {
        throw 'Raw-output parser fixture failed.'
    }
    Assert-Stage5ParserRejects ($fixture.Replace('instrumentation off', 'instrumentation on')) 'instrumentation enabled'
    Assert-Stage5ParserRejects ($fixture + "`nNodes searched: 24780049") 'duplicate node field'
    Assert-Stage5ParserRejects ($fixture.Replace('NPS   : 2478004', '')) 'missing NPS field'
    Assert-Stage5ParserRejects ($fixture.Replace('Time  : 10000 ms', 'Time  : 0 ms')) 'zero elapsed field'
    Assert-Stage5ParserRejects ($fixture + "`n" + $fixture.Substring(0, $fixture.IndexOf("`n"))) 'duplicate header'
    $nodeMismatchRejected = $false
    try { Assert-Stage5NodeCount ($script:CandidateNodes - 1) 'candidate' 'synthetic warm-up' } catch { $nodeMismatchRejected = $true }
    if (-not $nodeMismatchRejected) { throw 'Node-count failure fixture was accepted.' }

    $control = @(
        foreach ($elapsed in @(72000, 73000, 74000, 75000, 76000, 77000, 78000)) {
            [pscustomobject]@{ ElapsedMs = $elapsed; Nps = [math]::Floor($script:ControlNodes * 1000.0 / $elapsed); MainNodes = $script:ControlNodes }
        }
    )
    $candidatePass = @(
        foreach ($elapsed in @(60000, 61000, 62000, 63000, 64000, 65000, 66000)) {
            [pscustomobject]@{ ElapsedMs = $elapsed; Nps = [math]::Floor($script:CandidateNodes * 1000.0 / $elapsed); MainNodes = $script:CandidateNodes }
        }
    )
    $passingDecision = Get-Stage5Decision $control $candidatePass
    $expectedCandidateAggregate = [math]::Floor(($script:CandidateNodes * 7 * 1000.0) / (($candidatePass | Measure-Object -Property ElapsedMs -Sum).Sum))
    if ($passingDecision.Gate -ne 'PASS' -or
        $passingDecision.ControlElapsedMinMs -ne 72000 -or
        $passingDecision.ControlElapsedMedianMs -ne 75000 -or
        $passingDecision.ControlElapsedMaxMs -ne 78000 -or
        $passingDecision.CandidateElapsedMinMs -ne 60000 -or
        $passingDecision.CandidateElapsedMedianMs -ne 63000 -or
        $passingDecision.CandidateElapsedMaxMs -ne 66000 -or
        $passingDecision.CandidateMedianNps -ne [math]::Floor($script:CandidateNodes * 1000.0 / 63000) -or
        $passingDecision.CandidateAggregateNps -ne $expectedCandidateAggregate -or
        [math]::Abs($passingDecision.CandidateControlMedianElapsedRatio - 0.84) -gt 0.000001) {
        throw 'Passing decision, elapsed summaries, ratio, or aggregate NPS fixture failed.'
    }
    $candidateAtControlMinimum = @(
        foreach ($elapsed in @(59000, 65000, 70000, 72000, 74000, 76000, 78000)) {
            [pscustomobject]@{ ElapsedMs = $elapsed; Nps = [math]::Floor($script:CandidateNodes * 1000.0 / $elapsed); MainNodes = $script:CandidateNodes }
        }
    )
    if ((Get-Stage5Decision $control $candidateAtControlMinimum).Gate -ne 'STOP_BEFORE_STAGE_6') {
        throw 'Strict elapsed-boundary fixture failed.'
    }
    $candidateBelowMedianFloor = @(
        foreach ($elapsed in @(80000, 81000, 82000, 83000, 84000, 85000, 86000)) {
            [pscustomobject]@{ ElapsedMs = $elapsed; Nps = [math]::Floor($script:CandidateNodes * 1000.0 / $elapsed); MainNodes = $script:CandidateNodes }
        }
    )
    $slowControl = @(
        foreach ($elapsed in @(90000, 91000, 92000, 93000, 94000, 95000, 96000)) {
            [pscustomobject]@{ ElapsedMs = $elapsed; Nps = [math]::Floor($script:ControlNodes * 1000.0 / $elapsed); MainNodes = $script:ControlNodes }
        }
    )
    $medianFloorDecision = Get-Stage5Decision $slowControl $candidateBelowMedianFloor
    if ($medianFloorDecision.CandidateMedianElapsedBelowControlMinimum -ne $true -or
        $medianFloorDecision.CandidateMedianNpsFloorPass -ne $false -or
        $medianFloorDecision.Gate -ne 'STOP_BEFORE_STAGE_6') { throw 'Median NPS floor failure fixture failed.' }

    $candidateAggregateFloorFailure = @(
        foreach ($elapsed in @(70000, 70000, 70000, 70000, 110000, 110000, 110000)) {
            [pscustomobject]@{ ElapsedMs = $elapsed; Nps = [math]::Floor($script:CandidateNodes * 1000.0 / $elapsed); MainNodes = $script:CandidateNodes }
        }
    )
    $aggregateFloorDecision = Get-Stage5Decision $control $candidateAggregateFloorFailure
    if ($aggregateFloorDecision.CandidateMedianNpsFloorPass -ne $true -or
        $aggregateFloorDecision.CandidateAggregateNpsFloorPass -ne $false -or
        $aggregateFloorDecision.Gate -ne 'STOP_BEFORE_STAGE_6') { throw 'Aggregate NPS floor fixture failed.' }

    $validationRoot = Join-Path ([IO.Path]::GetTempPath()) "phase21-stage5-validation-$([guid]::NewGuid().ToString('N'))"
    if (Test-Path -LiteralPath $validationRoot) { throw 'Validation temporary directory collision.' }
    New-Item -ItemType Directory -Path $validationRoot | Out-Null
    try {
        $preservedRoot = Join-Path $validationRoot 'preserve'
        $emptyRoot = Join-Path $validationRoot 'empty'
        New-Item -ItemType Directory -Path $preservedRoot | Out-Null
        New-Item -ItemType Directory -Path $emptyRoot | Out-Null
        $sentinel = Join-Path $preservedRoot 'unowned.txt'
        Set-Content -LiteralPath $sentinel -Value 'preserve' -Encoding UTF8
        if (Remove-Stage5WorkRootIfEmpty $preservedRoot) { throw 'Cleanup removed a non-empty work root.' }
        if (-not (Test-Path -LiteralPath $sentinel -PathType Leaf)) { throw 'Cleanup deleted an unowned file.' }
        Remove-Item -LiteralPath $sentinel -Force
        if (-not (Remove-Stage5WorkRootIfEmpty $preservedRoot) -or (Test-Path -LiteralPath $preservedRoot)) {
            throw 'Cleanup did not remove an empty work root.'
        }
        if (-not (Remove-Stage5WorkRootIfEmpty $emptyRoot) -or (Test-Path -LiteralPath $emptyRoot)) {
            throw 'Cleanup did not remove an empty work root.'
        }

        $failureLog = Join-Path $validationRoot 'synthetic-failure.log'
        $powerShellPath = (Get-Process -Id $PID).Path
        $processFailureObserved = $false
        try { $null = Invoke-CheckedNative $powerShellPath @('-NoProfile', '-NonInteractive', '-Command', 'Write-Output "synthetic failure"; exit 7') $validationRoot $failureLog }
        catch { $processFailureObserved = $true }
        if (-not $processFailureObserved -or -not (Test-Path -LiteralPath $failureLog -PathType Leaf) -or
            (Get-Content -LiteralPath $failureLog -Raw) -notmatch 'synthetic failure') {
            throw 'Non-zero command failure was not raised with its output preserved.'
        }
    }
    finally {
        if (Test-Path -LiteralPath $validationRoot) { Remove-Item -LiteralPath $validationRoot -Recurse -Force }
    }

    Write-Host 'VALIDATION PASS: parser rejection, exact node checks, 16-call order, summaries, NPS floors, cleanup preservation, and failed-command logs.' -ForegroundColor Green
    Write-Host 'No benchmark-host probe, Git worktree, Maven build, JVM launch, or engine benchmark was performed.'
}

function Invoke-CheckedNative([string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory, [string]$LogPath) {
    $captured = New-Object System.Collections.Generic.List[string]
    $exitCode = 1
    Push-Location $WorkingDirectory
    try {
        try {
            & $Executable @Arguments 2>&1 | ForEach-Object {
                $line = $_.ToString()
                $captured.Add($line)
                Write-Host $line
            }
            if (Test-Path variable:LASTEXITCODE) { $exitCode = [int]$LASTEXITCODE }
        }
        catch {
            $captured.Add($_.ToString())
        }
    }
    finally {
        Pop-Location
    }
    if ($captured.Count -eq 0) { Set-Content -LiteralPath $LogPath -Value '' -Encoding UTF8 }
    else { Set-Content -LiteralPath $LogPath -Value $captured.ToArray() -Encoding UTF8 }
    if ($exitCode -ne 0) { throw "Command failed ($exitCode): $Executable $($Arguments -join ' ')" }
    return ,$captured.ToArray()
}

function Get-GitValue([string]$Repository, [string[]]$Arguments) {
    $value = & git.exe -C $Repository @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "Git command failed: git -C $Repository $($Arguments -join ' ')`n$($value -join "`n")" }
    return (($value | ForEach-Object { $_.ToString() }) -join "`n").Trim()
}

function Assert-NativeStage5Environment {
    if ($env:WSL_DISTRO_NAME -or $env:WSL_INTEROP) { throw 'Refusing WSL/WSL interop. Run from a normal native Windows PowerShell terminal.' }
    $platform = [Environment]::OSVersion.Platform.ToString()
    if ($platform -ne 'Win32NT') { throw "Expected native Windows PowerShell; platform is '$platform'." }
    $processPath = (Get-Process -Id $PID).Path
    if ($processPath -notmatch '(?i)\\(WindowsPowerShell|PowerShell)\\.*(powershell|pwsh)\.exe$') {
        throw "PowerShell host does not look like native Windows PowerShell: $processPath"
    }
    $repoPath = (Resolve-Path -LiteralPath $repoRoot).Path
    if ($repoPath -notmatch '^[A-Za-z]:\\' -or $repoPath -match '(?i)wsl') {
        throw "Checkout must be on a native Windows drive, not WSL: $repoPath"
    }

    $os = Get-CimInstance -ClassName Win32_OperatingSystem
    $cpu = Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1
    if ($os.Caption -notmatch '^Microsoft Windows 11 Pro$' -or [int]$os.BuildNumber -ne 26200) {
        throw "Required Windows 11 Pro build 26200; found '$($os.Caption)' build $($os.BuildNumber)."
    }
    if ($cpu.Name -notmatch '^AMD Ryzen 7 7700X(?:\s|$)') { throw "Required Ryzen 7 7700X; found '$($cpu.Name)'." }

    $powerPlan = (& powercfg.exe /getactivescheme 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $powerPlan -notmatch '(?i)381b4222-f694-41f0-9685-ff5bb260df2e') {
        throw "Required Balanced power plan; active plan was '$powerPlan'."
    }

    $process = Get-Process -Id $PID
    $logical = [int][Environment]::ProcessorCount
    if ($logical -lt 1 -or $logical -gt 62) { throw "Unexpected logical processor count: $logical." }
    $fullMask = ([long]1 -shl $logical) - 1
    $affinity = $process.ProcessorAffinity.ToInt64()
    if ($affinity -ne $fullMask) { throw "PowerShell process is explicitly affinity-limited ($affinity vs full mask $fullMask)." }

    $javaCommand = Get-Command java -CommandType Application -ErrorAction Stop | Select-Object -First 1
    $mavenCommand = Get-Command mvn -ErrorAction Stop | Select-Object -First 1
    $javaExe = $javaCommand.Source
    $mavenExe = if ($mavenCommand.Path) { $mavenCommand.Path } else { $mavenCommand.Source }
    $injectedOptionVariables = @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS')
    $injectedOptions = @($injectedOptionVariables | Where-Object { [Environment]::GetEnvironmentVariable($_) })
    if ($injectedOptions.Count -gt 0) {
        throw "Unset inherited JVM option variables before Stage 5 to prevent agents, JFR, or diagnostic flags: $($injectedOptions -join ', ')."
    }
    $javaVersionRaw = (& $javaExe -version 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'java -version failed.' }
    $javaPropertiesRaw = (& $javaExe -XshowSettings:properties -version 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'java properties query failed.' }
    $mavenVersionRaw = (& $mavenExe -version 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'mvn -version failed.' }

    $javaVersion = [regex]::Match($javaPropertiesRaw, '(?m)^\s*java\.version\s*=\s*(.+?)\s*$').Groups[1].Value.Trim()
    $javaRuntime = [regex]::Match($javaPropertiesRaw, '(?m)^\s*java\.runtime\.version\s*=\s*(.+?)\s*$').Groups[1].Value.Trim()
    $javaVendor = [regex]::Match($javaPropertiesRaw, '(?m)^\s*java\.vendor\s*=\s*(.+?)\s*$').Groups[1].Value.Trim()
    if ($javaVersion -ne '21.0.10' -or $javaRuntime -notmatch '^21\.0\.10\+7-LTS\b' -or $javaVendor -notmatch '(?i)Azul') {
        throw "Required Azul Zulu OpenJDK 21.0.10+7-LTS; found vendor='$javaVendor', version='$javaVersion', runtime='$javaRuntime'."
    }
    if ($mavenVersionRaw -notmatch '(?i)Java version:\s*21\.0\.10' -or $mavenVersionRaw -notmatch '(?i)Azul') {
        throw "Maven is not using the required Azul Java runtime:`n$mavenVersionRaw"
    }

    return [pscustomobject]@{
        CapturedUtc = [DateTime]::UtcNow.ToString('o')
        PowershellVersion = $PSVersionTable.PSVersion.ToString()
        PowershellPlatform = $platform
        PowershellProcessPath = $processPath
        WorkingDirectory = $repoPath
        ComputerName = $env:COMPUTERNAME
        OsCaption = $os.Caption
        OsVersion = $os.Version
        OsBuild = [int]$os.BuildNumber
        CpuName = $cpu.Name
        CpuCores = [int]$cpu.NumberOfCores
        CpuLogicalProcessors = [int]$cpu.NumberOfLogicalProcessors
        ActivePowerPlanRaw = $powerPlan
        PowerPlan = 'Balanced'
        ExplicitAffinity = $false
        AffinityMask = $affinity
        FullAffinityMask = $fullMask
        JavaExecutable = $javaExe
        JavaVersion = $javaVersion
        JavaRuntimeVersion = $javaRuntime
        JavaVendor = $javaVendor
        JavaVersionRaw = $javaVersionRaw
        JavaPropertiesRaw = $javaPropertiesRaw
        MavenExecutable = $mavenExe
        MavenVersionRaw = $mavenVersionRaw
        MavenOpts = $env:MAVEN_OPTS
        JavaToolOptions = $env:JAVA_TOOL_OPTIONS
        JdkJavaOptions = $env:JDK_JAVA_OPTIONS
        UnderscoreJavaOptions = $env:_JAVA_OPTIONS
        JavaHome = $env:JAVA_HOME
        JvmArguments = $script:JvmArguments
        BuildArguments = $script:BuildArguments
        Threads = 1
        HashMb = 16
        Evaluator = 'Classical'
        PawnHashSizeMb = 1
        OwnBook = $false
        Syzygy = $false
        MultiPv = 1
        Contempt = 0
        BenchDepth = 13
        Instrumentation = 'off (asserted from BenchRunner raw-output header)'
        TimingArmOrder = 'control warm-up, candidate warm-up, then control/candidate alternating for measured ordinals 1..7'
    }
}

function Assert-InvokingCheckout([string]$Repository) {
    $branch = Get-GitValue $Repository @('branch', '--show-current')
    if ($branch -ne 'phase/21-zero-window-search') { throw "Expected branch phase/21-zero-window-search; found '$branch'." }
    $head = Get-GitValue $Repository @('rev-parse', 'HEAD')
    $statusLines = @(& git.exe -C $Repository status --porcelain=v1 --untracked-files=all 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect invoking checkout status.' }
    $unexpected = @($statusLines | Where-Object {
        if ($_.Length -lt 4) { $true }
        else {
            $code = $_.Substring(0, 2)
            $path = $_.Substring(3).Replace('\', '/')
            -not ($code -eq '??' -and $path.StartsWith('.claude/agent-memory/', [StringComparison]::Ordinal))
        }
    })
    if ($unexpected.Count -gt 0) { throw "Invoking checkout must be clean except .claude/agent-memory/: $($unexpected -join '; ')" }
    return [pscustomobject]@{ Branch = $branch; Head = $head; AllowedUntracked = '.claude/agent-memory/' }
}

function Assert-CleanStage5Worktree([string]$Worktree, [string]$Arm) {
    $status = Get-GitValue $Worktree @('status', '--porcelain=v1', '--untracked-files=all')
    if ($status) { throw "$Arm worktree is dirty: $status" }
}

function Assert-CanonicalBenchSource([string]$Worktree, [string]$Arm) {
    $benchFile = Join-Path $Worktree 'engine-uci\src\main\java\coeusyk\game\chess\uci\BenchRunner.java'
    $hash = (Get-FileHash -LiteralPath $benchFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $script:BenchRunnerSha256) { throw "$Arm BenchRunner source is not the frozen 31-position corpus (SHA $hash)." }
    $text = Get-Content -LiteralPath $benchFile -Raw
    $array = [regex]::Match($text, '(?s)private\s+static\s+final\s+String\[\]\s+BENCH_FENS\s*=\s*\{(.*?)\};')
    if (-not $array.Success) { throw "$Arm BENCH_FENS declaration was not found." }
    $fens = [regex]::Matches($array.Groups[1].Value, '"[^"]+"')
    if ($fens.Count -ne 31) { throw "$Arm corpus contains $($fens.Count) FEN literals, expected 31." }
    return [pscustomobject]@{ BenchRunnerSha256 = $hash; Positions = $fens.Count }
}

function Save-Json($Value, [string]$Path) {
    $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Add-RunRecord([System.Collections.Generic.List[object]]$Rows, $Row, [string]$CsvPath) {
    $Rows.Add($Row)
    $Rows.ToArray() | Export-Csv -LiteralPath $CsvPath -NoTypeInformation -Encoding UTF8
}

function Run-Stage5Bench([string]$Arm, [string]$Phase, [int]$Ordinal, [int]$Sequence,
                         [string]$JarPath, [string]$JavaExe, [string]$OutputDir,
                         [System.Collections.Generic.List[object]]$Rows) {
    $label = if ($Ordinal -gt 0) { '{0}-{1:D2}' -f $Arm, $Ordinal } else { '{0}-{1}' -f $Phase, $Arm }
    $rawName = '{0:D2}-{1}.raw.txt' -f $Sequence, $label
    $rawPath = Join-Path $OutputDir $rawName
    Write-Host "RUN ${Sequence}: $label"
    $output = Invoke-CheckedNative $JavaExe ($script:JvmArguments + @('-jar', $JarPath, '--bench-raw', '13')) $OutputDir $rawPath
    $parsed = Parse-Stage5BenchOutput ($output -join "`n")
    Assert-Stage5NodeCount $parsed.MainNodes $Arm $label
    $row = [pscustomobject]@{
        Sequence = $Sequence
        Phase = $Phase
        Arm = $Arm
        Ordinal = $Ordinal
        Measured = ($Phase -eq 'measured')
        SourceCommit = if ($Arm -eq 'control') { $script:ControlCommit } else { $script:CandidateCommit }
        JarSha256 = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash.ToLowerInvariant()
        MainNodes = $parsed.MainNodes
        Qnodes = $parsed.Qnodes
        QnodesAvailable = $false
        ElapsedMs = $parsed.ElapsedMs
        Nps = $parsed.Nps
        Depth = $parsed.Depth
        Positions = $parsed.Positions
        HashMb = $parsed.HashMb
        Evaluator = $parsed.Evaluator
        Instrumentation = $parsed.Instrumentation
        RawOutput = $rawName
        RecordedUtc = [DateTime]::UtcNow.ToString('o')
    }
    Add-RunRecord $Rows $row (Join-Path $OutputDir 'runs.csv')
    return $row
}

function Print-Stage5Schedule([object[]]$Schedule) {
    Write-Host 'Frozen invocation order (including unmeasured warm-ups):'
    $sequence = 0
    foreach ($item in $Schedule) {
        $sequence++
        $label = if ($item.Ordinal -gt 0) { '{0}-{1:D2}' -f $item.Arm, $item.Ordinal } else { '{0}-{1}' -f $item.Phase, $item.Arm }
        Write-Host ('  {0:D2}. {1}' -f $sequence, $label)
    }
}

if ($ValidateOnly) {
    Invoke-Stage5ValidationOnly
    return
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$schedule = @(New-Stage5Schedule)

$initialEnvironment = Assert-NativeStage5Environment
$invokingCheckout = Assert-InvokingCheckout $repoRoot
$javaExe = $initialEnvironment.JavaExecutable
$mavenExe = $initialEnvironment.MavenExecutable

foreach ($commit in @($script:ControlCommit, $script:CandidateCommit)) {
    $null = Get-GitValue $repoRoot @('cat-file', '-e', "$commit`^{commit}")
}

$runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
$outputDir = Join-Path $repoRoot "tools\results\phase21-stage5\$runId"
if (Test-Path -LiteralPath $outputDir) { throw "Unique result directory already exists: $outputDir" }
$workRoot = Join-Path $env:TEMP "phase21-stage5-builds-$runId"
if (Test-Path -LiteralPath $workRoot) { throw "Unique temporary work root already exists: $workRoot" }
New-Item -ItemType Directory -Path $outputDir | Out-Null
$transcriptPath = Join-Path $outputDir 'runner.log'
$controlWorktree = Join-Path $workRoot 'control'
$candidateWorktree = Join-Path $workRoot 'candidate'
$addedWorktrees = New-Object System.Collections.Generic.List[string]
$runRows = New-Object System.Collections.Generic.List[object]
$sequence = 0
$workRootCreated = $false
$transcriptStarted = $false

try {
    Start-Transcript -LiteralPath $transcriptPath | Out-Null
    $transcriptStarted = $true
    Print-Stage5Schedule $schedule
    $environment = Assert-NativeStage5Environment
    $javaExe = $environment.JavaExecutable
    $mavenExe = $environment.MavenExecutable
    $environment | Add-Member -NotePropertyName InvokingBranch -NotePropertyValue $invokingCheckout.Branch
    $environment | Add-Member -NotePropertyName InvokingHead -NotePropertyValue $invokingCheckout.Head
    $environment | Add-Member -NotePropertyName RunId -NotePropertyValue $runId
    $environment | Add-Member -NotePropertyName NoCompetingCpuWorkload -NotePropertyValue 'Operator confirmation requested before measured series'
    Save-Json $environment (Join-Path $outputDir 'environment.json')

    $identity = [ordered]@{
        invoking_branch = $invokingCheckout.Branch
        invoking_head = $invokingCheckout.Head
        allowed_untracked = $invokingCheckout.AllowedUntracked
        control_commit = $script:ControlCommit
        candidate_commit = $script:CandidateCommit
        benchrunner_source_sha256_required = $script:BenchRunnerSha256
        worktrees = [ordered]@{ control = $controlWorktree; candidate = $candidateWorktree }
        build_command = 'mvn -B -pl engine-core,engine-uci -am package -DskipTests'
        jar_relative_path = 'engine-uci\target\engine-uci-0.6.0-SNAPSHOT.jar'
        jvm_arguments = $script:JvmArguments
        run_order = @($schedule | ForEach-Object { '{0}-{1}-{2:D2}' -f $_.Phase, $_.Arm, $_.Ordinal })
        measurement_protocol = 'control warm-up, candidate warm-up, then control/candidate alternating for ordinals 1..7'
        elapsed_source = 'BenchRunner --bench-raw Time field; excludes build and warm-up work'
        nps_floor = $script:NpsFloor
        qnodes = 'BenchRunner --bench-raw does not emit qnodes; recorded as unavailable'
    }
    Save-Json $identity (Join-Path $outputDir 'identity.json')
    $schedule | Select-Object @{n='Sequence';e={ [array]::IndexOf($schedule, $_) + 1 }}, Phase, Arm, Ordinal, Measured |
        Export-Csv -LiteralPath (Join-Path $outputDir 'planned-schedule.csv') -NoTypeInformation -Encoding UTF8

    New-Item -ItemType Directory -Path $workRoot | Out-Null
    $workRootCreated = $true
    Write-Host "Creating detached native worktrees under $workRoot"
    $gitOut = & git.exe -C $repoRoot worktree add --detach $controlWorktree $script:ControlCommit 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Control worktree creation failed: $($gitOut -join "`n")" }
    $addedWorktrees.Add($controlWorktree)
    $gitOut = & git.exe -C $repoRoot worktree add --detach $candidateWorktree $script:CandidateCommit 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Candidate worktree creation failed: $($gitOut -join "`n")" }
    $addedWorktrees.Add($candidateWorktree)

    foreach ($arm in @(
        [pscustomobject]@{ Name = 'control'; Path = $controlWorktree },
        [pscustomobject]@{ Name = 'candidate'; Path = $candidateWorktree }
    )) {
        $expectedCommit = if ($arm.Name -eq 'control') { $script:ControlCommit } else { $script:CandidateCommit }
        $actualCommit = Get-GitValue $arm.Path @('rev-parse', 'HEAD')
        if ($actualCommit -ne $expectedCommit) { throw "$($arm.Name) worktree HEAD mismatch: $actualCommit." }
        Assert-CleanStage5Worktree $arm.Path $arm.Name
        $corpus = Assert-CanonicalBenchSource $arm.Path $arm.Name
        $buildLog = Join-Path $outputDir "$($arm.Name)-build.log"
        $jarPath = Join-Path $arm.Path 'engine-uci\target\engine-uci-0.6.0-SNAPSHOT.jar'
        if (Test-Path -LiteralPath (Split-Path -Parent $jarPath)) { throw "$($arm.Name) fresh worktree unexpectedly contains a target directory." }
        Write-Host "Building $($arm.Name) from $actualCommit with $mavenExe"
        $null = Invoke-CheckedNative $mavenExe $script:BuildArguments $arm.Path $buildLog
        Assert-CleanStage5Worktree $arm.Path $arm.Name
        if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw "$($arm.Name) JAR not found at $jarPath" }
        $jarHash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $arm | Add-Member -NotePropertyName SourceCommit -NotePropertyValue $actualCommit
        $arm | Add-Member -NotePropertyName BenchRunnerSha256 -NotePropertyValue $corpus.BenchRunnerSha256
        $arm | Add-Member -NotePropertyName JarPath -NotePropertyValue $jarPath
        $arm | Add-Member -NotePropertyName JarSha256 -NotePropertyValue $jarHash
        if ($arm.Name -eq 'control') { $control = $arm } else { $candidate = $arm }
        Write-Host "$($arm.Name) JAR SHA-256: $jarHash"
    }
    $identity['control_jar_sha256'] = $control.JarSha256
    $identity['candidate_jar_sha256'] = $candidate.JarSha256
    $identity['control_source_commit'] = $control.SourceCommit
    $identity['candidate_source_commit'] = $candidate.SourceCommit
    $identity['control_benchrunner_source_sha256'] = $control.BenchRunnerSha256
    $identity['candidate_benchrunner_source_sha256'] = $candidate.BenchRunnerSha256
    $identity['source_tree_status'] = 'clean before and after each build'
    Save-Json $identity (Join-Path $outputDir 'identity.json')

    foreach ($arm in @($control, $candidate)) {
        $sequence++
        $null = Run-Stage5Bench $arm.Name 'warmup' 0 $sequence $arm.JarPath $javaExe $outputDir $runRows
    }

    Write-Host ''
    Write-Host 'Both discarded warm-ups completed. The measured order is frozen and will not be adapted.'
    Write-Host 'Confirm this is the specified idle host with no competing CPU-intensive workload.' -ForegroundColor Yellow
    $confirmation = Read-Host 'Type RUN-STAGE5-NATIVE to start the 14 measured invocations'
    if ($confirmation -cne 'RUN-STAGE5-NATIVE') { throw 'Operator did not confirm; measured series not started.' }
    $environment.NoCompetingCpuWorkload = 'Operator confirmed immediately before the measured series'
    $environment | Add-Member -NotePropertyName ConfirmationUtc -NotePropertyValue ([DateTime]::UtcNow.ToString('o'))
    Save-Json $environment (Join-Path $outputDir 'environment.json')

    for ($i = 1; $i -le 7; $i++) {
        $sequence++
        $null = Run-Stage5Bench 'control' 'measured' $i $sequence $control.JarPath $javaExe $outputDir $runRows
        $sequence++
        $null = Run-Stage5Bench 'candidate' 'measured' $i $sequence $candidate.JarPath $javaExe $outputDir $runRows
    }

    $controlRuns = @($runRows | Where-Object { $_.Phase -eq 'measured' -and $_.Arm -eq 'control' })
    $candidateRuns = @($runRows | Where-Object { $_.Phase -eq 'measured' -and $_.Arm -eq 'candidate' })
    $summary = Get-Stage5Decision $controlRuns $candidateRuns
    $summary | Add-Member -NotePropertyName DecisionUtc -NotePropertyValue ([DateTime]::UtcNow.ToString('o'))
    $summary | Add-Member -NotePropertyName ControlElapsedMs -NotePropertyValue @($controlRuns | ForEach-Object { $_.ElapsedMs })
    $summary | Add-Member -NotePropertyName ControlNps -NotePropertyValue @($controlRuns | ForEach-Object { $_.Nps })
    $summary | Add-Member -NotePropertyName CandidateElapsedMs -NotePropertyValue @($candidateRuns | ForEach-Object { $_.ElapsedMs })
    $summary | Add-Member -NotePropertyName CandidateNps -NotePropertyValue @($candidateRuns | ForEach-Object { $_.Nps })
    Save-Json $summary (Join-Path $outputDir 'summary.json')

    $summaryRows = @(
        [pscustomobject]@{ Arm='control'; ElapsedMinMs=$summary.ControlElapsedMinMs; ElapsedMedianMs=$summary.ControlElapsedMedianMs; ElapsedMaxMs=$summary.ControlElapsedMaxMs; MedianNps=$summary.ControlMedianNps; AggregateNps=$summary.ControlAggregateNps },
        [pscustomobject]@{ Arm='candidate'; ElapsedMinMs=$summary.CandidateElapsedMinMs; ElapsedMedianMs=$summary.CandidateElapsedMedianMs; ElapsedMaxMs=$summary.CandidateElapsedMaxMs; MedianNps=$summary.CandidateMedianNps; AggregateNps=$summary.CandidateAggregateNps }
    )
    $summaryRows | Export-Csv -LiteralPath (Join-Path $outputDir 'summary.csv') -NoTypeInformation -Encoding UTF8
    Write-Host "Stage 5 gate: $($summary.Gate)"
    Write-Host "Candidate median elapsed / control median: $($summary.CandidateControlMedianElapsedRatio)"
    Write-Host "Candidate median NPS: $($summary.CandidateMedianNps); aggregate NPS: $($summary.CandidateAggregateNps); floor: $($script:NpsFloor)"
    Write-Host "Results: $outputDir"
    if ($summary.Gate -ne 'PASS') { Write-Host 'Stage 5 stops here; do not start Stage 6.' -ForegroundColor Yellow }
}
finally {
    $cleanupWorktrees = @($addedWorktrees.ToArray())
    [array]::Reverse($cleanupWorktrees)
    foreach ($worktree in $cleanupWorktrees) {
        try {
            & git.exe -C $repoRoot worktree remove $worktree 2>&1 | ForEach-Object { Write-Host $_ }
            if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove temporary worktree $worktree; preserving it for inspection." }
        }
        catch { Write-Warning "Worktree cleanup error for ${worktree}: $_" }
    }
    if ($workRootCreated -and (Test-Path -LiteralPath $workRoot)) {
        try {
            if (-not (Remove-Stage5WorkRootIfEmpty $workRoot)) {
                Write-Warning "Temporary work root is non-empty; preserving it for inspection: $workRoot"
            }
        }
        catch { Write-Warning "Could not remove empty temporary work root $workRoot" }
    }
    if ($transcriptStarted) { try { Stop-Transcript | Out-Null } catch { } }
}
