<#
.SYNOPSIS
    Empirically determine the cutechess-cli -concurrency value that maximizes completed
    games/hour on the current machine, with engine strength held constant.

.DESCRIPTION
    Runs a short, fixed-length, non-SPRT match (same JAR on both sides, Threads=1 on both
    sides, the same TC and openings you'd use in production) at each concurrency level in
    -ConcurrencyLevels, in order. After each step it measures games/hour, average game
    duration (from real per-move PGN time annotations, not a wall-clock estimate), average
    CPU%/RAM%/JVM-process-count sampled while that step ran, and scans for time forfeits /
    crashes / disconnects. It stops increasing concurrency the moment any step shows RAM
    pressure, an abnormal termination, or games/hour that stopped improving meaningfully --
    whichever comes first -- and recommends the last stable, still-improving step.

    This does not change engine strength, search, evaluation, Threads, or TC. It only
    varies cutechess-cli's own -concurrency to answer a pure throughput question. It does
    not modify tools/sprt.ps1 or any other existing script.

    Run from the chess-engine/ directory:
        .\tools\benchmark_concurrency.ps1 -Jar engine-uci\target\engine-uci-X.Y.Z.jar

.PARAMETER Jar
    Path to the engine JAR. The SAME jar is used on both sides of every game -- this
    benchmark measures infrastructure throughput, not engine strength.

.PARAMETER ConcurrencyLevels
    The cutechess -concurrency values to test, in the order to test them (default:
    2,4,6,8,10,12,14). The sweep stops early per the stopping criteria below; it does not
    necessarily run every value in this list.

.PARAMETER GamesPerStep
    Number of games to play at each concurrency level (default: 20). Kept small on purpose
    -- this is a throughput measurement, not a strength test, so it does not need SPRT-scale
    sample sizes. Raise it if games/hour looks noisy between steps.

.PARAMETER TC
    Time control, in cutechess-cli's tc= format (default: "60+0.6", the project's standard
    long TC). Must match what you actually run in production for the result to be
    representative -- this benchmark does not change your TC for you.

.PARAMETER HashMb
    UCI Hash size in MB applied to both engines (default: 64, matching this project's
    engine default). Explicit here (unlike tools/sprt.ps1, which never logs Hash) so every
    run of this script is self-documenting in its own output.

.PARAMETER OpeningsFile
    Path to an EPD opening book. Auto-detected from tools/noob_3moves.epd if empty, same
    as tools/sprt.ps1.

.PARAMETER RamStopPercent
    Stop increasing concurrency once RAM usage (sampled during a step) reaches or exceeds
    this percentage (default: 92). This is deliberately the primary stop signal, not CPU --
    see docs/architecture/case-studies/2026-07-17-sprt-throughput-investigation.md for why.

.PARAMETER MinImprovementPercent
    Stop increasing concurrency once a step's games/hour improves by less than this percent
    over the previous step (default: 10) -- diminishing returns, not worth the added RAM/
    complexity risk of going further.

.EXAMPLE
    .\tools\benchmark_concurrency.ps1 -Jar engine-uci\target\engine-uci-0.5.9-SNAPSHOT.jar

.EXAMPLE
    .\tools\benchmark_concurrency.ps1 -Jar engine-uci\target\engine-uci.jar `
        -ConcurrencyLevels 2,4,6,8 -GamesPerStep 30 -RamStopPercent 90
#>
param(
    [Parameter(Mandatory)][string]$Jar,
    [int[]]$ConcurrencyLevels = @(2, 4, 6, 8, 10, 12, 14),
    [int]$GamesPerStep = 20,
    [string]$TC = "60+0.6",
    [int]$HashMb = 64,
    [string]$OpeningsFile = "",
    [double]$RamStopPercent = 92,
    [double]$MinImprovementPercent = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ─── Locate cutechess-cli ────────────────────────────────────────────────────
$Cutechess = $env:CUTECHESS
if (-not $Cutechess) {
    $cmd = Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue
    $Cutechess = if ($cmd) { $cmd.Source } else { $null }
}
if (-not $Cutechess -or -not (Test-Path $Cutechess)) {
    Write-Error "cutechess-cli not found. Set `$env:CUTECHESS or add cutechess-cli.exe to PATH."
    exit 1
}

# ─── Locate Java ────────────────────────────────────────────────────────────
$Java = if ($env:JAVA) {
    Join-Path $env:JAVA 'bin\java.exe'
} elseif ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    'java'
}

# ─── Resolve JAR ─────────────────────────────────────────────────────────────
$JarResolved = Resolve-Path $Jar -ErrorAction SilentlyContinue
if (-not $JarResolved) { Write-Error "Engine JAR not found: $Jar"; exit 1 }

# ─── Opening book ────────────────────────────────────────────────────────────
if ($OpeningsFile -eq "") {
    $defaultBook = Join-Path $PSScriptRoot 'noob_3moves.epd'
    if (Test-Path $defaultBook) { $OpeningsFile = $defaultBook }
}
$openingsArgs = @()
if ($OpeningsFile -ne "" -and (Test-Path $OpeningsFile)) {
    $openingsArgs = @("-openings", "file=$OpeningsFile", "format=epd", "order=random", "plies=4")
}

# ─── Output paths ────────────────────────────────────────────────────────────
$ResultsDir = Join-Path $PSScriptRoot 'results'
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }
$TS = Get-Date -Format 'yyyyMMdd_HHmmss'
$SummaryCsv = Join-Path $ResultsDir "concurrency_benchmark_${TS}.csv"

Write-Host "Concurrency benchmark: JAR=$($JarResolved.Path)  TC=$TC  Hash=${HashMb}MB  Threads=1 (both sides)  GamesPerStep=$GamesPerStep"
Write-Host "Levels to test: $($ConcurrencyLevels -join ', ')"
Write-Host "Stop if: RAM >= $RamStopPercent%, an abnormal termination appears, or games/hour improves by less than $MinImprovementPercent% over the previous step."
Write-Host "Summary CSV: $SummaryCsv"
Write-Host ""

# ─── Per-step measurement helpers ────────────────────────────────────────────

function Get-CurrentCpuPercent {
    try {
        $sample = Get-Counter '\Processor(_Total)\% Processor Time' -ErrorAction Stop
        return [double]$sample.CounterSamples[0].CookedValue
    } catch {
        return $null
    }
}

function Get-CurrentRamPercent {
    try {
        $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
        $usedPercent = 100.0 - (100.0 * $os.FreePhysicalMemory / $os.TotalVisibleMemorySize)
        return [double]$usedPercent
    } catch {
        return $null
    }
}

function Get-CurrentJavaProcessCount {
    $procs = Get-Process -Name 'java' -ErrorAction SilentlyContinue
    if ($null -eq $procs) { return 0 }
    return @($procs).Count
}

# Parses per-move {score/depth Xs} annotations from a PGN and returns average game
# duration in seconds -- the same technique used in the throughput case study, not a
# wall-clock estimate.
function Get-AverageGameDurationSeconds {
    param([string]$PgnPath)
    if (-not (Test-Path $PgnPath)) { return 0 }
    $text = Get-Content -Path $PgnPath -Raw -ErrorAction SilentlyContinue
    if ([string]::IsNullOrEmpty($text)) { return 0 }
    $matches = [System.Text.RegularExpressions.Regex]::Matches($text, '\{[^}]*?\s([\d.]+)s\}')
    if ($matches.Count -eq 0) { return 0 }
    # Each game's total time = sum of its own move times. Games are separated by
    # [Event tags; splitting on that boundary keeps per-game totals correct rather than
    # summing the whole file's move times into one number.
    $gameChunks = [System.Text.RegularExpressions.Regex]::Split($text, '(?=\[Event )')
    $totalSeconds = 0.0
    $gameCount = 0
    foreach ($chunk in $gameChunks) {
        if ($chunk.Trim().Length -eq 0) { continue }
        $chunkMatches = [System.Text.RegularExpressions.Regex]::Matches($chunk, '\{[^}]*?\s([\d.]+)s\}')
        if ($chunkMatches.Count -eq 0) { continue }
        $gameSeconds = 0.0
        foreach ($m in $chunkMatches) { $gameSeconds += [double]$m.Groups[1].Value }
        $totalSeconds += $gameSeconds
        $gameCount++
    }
    if ($gameCount -eq 0) { return 0 }
    return $totalSeconds / $gameCount
}

# ─── Run one concurrency step ────────────────────────────────────────────────

function Invoke-ConcurrencyStep {
    param([int]$Concurrency)

    $stepPgn = Join-Path $ResultsDir "concurrency_benchmark_${TS}_c${Concurrency}.pgn"
    $stepLog = Join-Path $ResultsDir "concurrency_benchmark_${TS}_c${Concurrency}.log"

    $ccArgs = @(
        "-engine", "name=A", "cmd=$Java", "arg=-jar", "arg=$($JarResolved.Path)", "proto=uci",
        "option.Threads=1", "option.Hash=$HashMb",
        "-engine", "name=B", "cmd=$Java", "arg=-jar", "arg=$($JarResolved.Path)", "proto=uci",
        "option.Threads=1", "option.Hash=$HashMb",
        "-each", "tc=$TC",
        "-games", "$GamesPerStep",
        "-repeat",
        "-recover",
        "-resign", "movecount=5", "score=400",
        "-draw", "movenumber=40", "movecount=8", "score=10",
        "-concurrency", "$Concurrency",
        "-ratinginterval", "1",
        "-pgnout", $stepPgn
    )
    if ($openingsArgs.Count -gt 0) { $ccArgs += $openingsArgs }

    Write-Host "--- Concurrency $Concurrency : starting $GamesPerStep games ---"

    $cpuSamples = New-Object System.Collections.Generic.List[double]
    $ramSamples = New-Object System.Collections.Generic.List[double]
    $jvmSamples = New-Object System.Collections.Generic.List[int]
    $abnormalReasons = New-Object System.Collections.Generic.List[string]
    $finishedCount = 0

    $logWriter = New-Object System.IO.StreamWriter($stepLog, $false)
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        & $Cutechess @ccArgs 2>&1 | ForEach-Object {
            $line = $_
            Write-Host $line
            $logWriter.WriteLine($line)

            if ($line -match '^Finished game \d+ ') {
                $finishedCount++
                # The reason suffix (e.g. "{White wins by adjudication}") is expected but
                # kept optional here so a game still counts even if the text doesn't match.
                if ($line -match '\{(.*)\}\s*$') {
                    $reason = $Matches[1]
                    if ($reason -match '(?i)forfeit|disconnect|crash|stall') {
                        $abnormalReasons.Add($reason)
                    }
                }

                $cpu = Get-CurrentCpuPercent
                if ($null -ne $cpu) { $cpuSamples.Add($cpu) }
                $ram = Get-CurrentRamPercent
                if ($null -ne $ram) { $ramSamples.Add($ram) }
                $jvmSamples.Add((Get-CurrentJavaProcessCount))
            }
        }
    } finally {
        $stopwatch.Stop()
        $logWriter.Flush()
        $logWriter.Close()
    }

    $elapsedHours = $stopwatch.Elapsed.TotalHours
    $gamesPerHour = if ($elapsedHours -gt 0) { $finishedCount / $elapsedHours } else { 0 }
    $avgDurationSeconds = Get-AverageGameDurationSeconds -PgnPath $stepPgn

    $avgCpu = if ($cpuSamples.Count -gt 0) { ($cpuSamples | Measure-Object -Average).Average } else { 0 }
    $avgRam = if ($ramSamples.Count -gt 0) { ($ramSamples | Measure-Object -Average).Average } else { 0 }
    $maxRam = if ($ramSamples.Count -gt 0) { ($ramSamples | Measure-Object -Maximum).Maximum } else { 0 }
    $avgJvm = if ($jvmSamples.Count -gt 0) { ($jvmSamples | Measure-Object -Average).Average } else { 0 }

    $stability = "OK"
    if ($abnormalReasons.Count -gt 0) {
        $uniqueReasons = $abnormalReasons | Select-Object -Unique -First 3
        $stability = "UNSTABLE: " + ($uniqueReasons -join '; ')
    }

    return [PSCustomObject]@{
        Concurrency       = $Concurrency
        GamesCompleted    = $finishedCount
        GamesPerHour      = [math]::Round($gamesPerHour, 1)
        AvgGameDurationS  = [math]::Round($avgDurationSeconds, 1)
        AvgCpuPercent     = [math]::Round($avgCpu, 1)
        AvgRamPercent     = [math]::Round($avgRam, 1)
        MaxRamPercent     = [math]::Round($maxRam, 1)
        AvgJvmProcesses   = [math]::Round($avgJvm, 1)
        SearchThreads     = 1
        Stability         = $stability
        Recommendation    = ""
    }
}

# ─── Sweep, checking stop criteria after every step ──────────────────────────

$results = New-Object System.Collections.Generic.List[object]
$stopReason = $null

foreach ($level in $ConcurrencyLevels) {
    $result = Invoke-ConcurrencyStep -Concurrency $level
    $results.Add($result)

    $stepSummaryFormat = "Concurrency {0}: {1} games/hour, avg duration {2}s, CPU {3}%, RAM avg/max {4}%/{5}%, {6} JVMs, {7}"
    $stepSummary = $stepSummaryFormat -f $result.Concurrency, $result.GamesPerHour, $result.AvgGameDurationS, $result.AvgCpuPercent, $result.AvgRamPercent, $result.MaxRamPercent, $result.AvgJvmProcesses, $result.Stability
    Write-Host ""
    Write-Host $stepSummary
    Write-Host ""

    if ($result.Stability -ne "OK") {
        $stopReason = "abnormal termination detected (" + $result.Stability + ")"
        break
    }
    if ($result.MaxRamPercent -ge $RamStopPercent) {
        $stopReason = "RAM reached $($result.MaxRamPercent)% (threshold $RamStopPercent%)"
        break
    }
    if ($results.Count -ge 2) {
        $prev = $results[$results.Count - 2]
        if ($prev.GamesPerHour -gt 0) {
            $improvementPercent = 100.0 * ($result.GamesPerHour - $prev.GamesPerHour) / $prev.GamesPerHour
            if ($improvementPercent -lt $MinImprovementPercent) {
                $stopReason = "games/hour improved only $([math]::Round($improvementPercent,1))% over concurrency $($prev.Concurrency) (threshold $MinImprovementPercent%)"
                break
            }
        }
    }
}

# ─── Recommendation: the last stable, still-improving step ──────────────────

$recommendedIndex = $results.Count - 1
if ($stopReason -and $results.Count -gt 1 -and $results[$results.Count - 1].Stability -ne "OK") {
    # The failing step itself is never the recommendation -- recommend the one before it.
    $recommendedIndex = $results.Count - 2
} elseif ($stopReason -and $results.Count -gt 1) {
    # Diminishing-returns or RAM-threshold stop: the step that triggered the stop is
    # still the best-known-good throughput number (RAM was fine and it still ran cleanly
    # up to the RAM threshold, or the tiny improvement was still an improvement) unless it
    # was itself over the RAM ceiling.
    $last = $results[$results.Count - 1]
    if ($last.MaxRamPercent -ge $RamStopPercent) { $recommendedIndex = $results.Count - 2 }
}
if ($recommendedIndex -lt 0) { $recommendedIndex = 0 }

for ($i = 0; $i -lt $results.Count; $i++) {
    if ($i -eq $recommendedIndex) {
        $results[$i].Recommendation = "<-- recommended"
    }
}

# ─── Print summary table and write CSV ───────────────────────────────────────

Write-Host ""
Write-Host "=== Concurrency benchmark summary ==="
$results | Format-Table -Property Concurrency, GamesPerHour, AvgGameDurationS, AvgCpuPercent, AvgRamPercent, MaxRamPercent, AvgJvmProcesses, SearchThreads, Stability, Recommendation -AutoSize
if ($stopReason) {
    Write-Host "Sweep stopped early: $stopReason"
} else {
    Write-Host "Sweep completed all requested concurrency levels without triggering a stop condition."
    Write-Host "Consider extending -ConcurrencyLevels higher and re-running -- the true optimum may not have been reached yet."
}
Write-Host ""
Write-Host "Recommended concurrency: $($results[$recommendedIndex].Concurrency)"

$results | Export-Csv -Path $SummaryCsv -NoTypeInformation
Write-Host ""
Write-Host "Summary written to: $SummaryCsv"
