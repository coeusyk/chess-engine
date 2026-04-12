<#
.SYNOPSIS
    CLOP (Confident Local Optimization) — fixed-baseline methodology.

.DESCRIPTION
    Tunes evaluation parameters by running engine-vs-engine matches.  Each iteration:
      1. Samples a candidate parameter vector (Gaussian around current best, σ=(max-min)/6).
      2. Writes the vector to a temp override file.
      3. Runs cutechess-cli: candidate (with --param-overrides) vs frozen baseline JAR.
      4. Parses W/D/L and computes a noisy Elo estimate.
      5. Updates the best vector if candidate is better.
      6. Appends a row to clop_results.csv.

    METHODOLOGY — fixed baseline is mandatory
    -----------------------------------------
    The baseline JAR must be a frozen build that never changes across all iterations.
    Candidate and baseline must NEVER be the same JAR — same-JAR self-play produces a
    flat win-rate surface regardless of parameter values and makes all CLOP output noise.

    CLOP is a response-surface optimizer, not a hypothesis test. Many cheap noisy
    iterations beat a few expensive ones. Default: 300 iterations of 16 games each.
    Do NOT increase GamesPerIteration to "improve accuracy" — use more iterations instead.
    Confirm CLOP results with SPRT after convergence, not a longer CLOP run.

    --AllowSlowConfig
    -----------------
    Bypasses the GamesPerIteration > 50 and TC > 30+0.3 guardrails for
    final confirmation runs only. Does NOT bypass the Iterations < 100 check.

.PARAMETER Params
    Path to a JSON file describing parameters. Each entry must have:
    { "name": "TEMPO", "current": 21, "min": 5, "max": 40, "step": 1 }
    Default: tools/clop_params.json (relative to script location).

.PARAMETER BaselineJar
    Path to the frozen baseline JAR. Default: tools/baseline-v0.5.6-pretune.jar.
    Hard error if this file does not exist.

.PARAMETER CandidateJar
    Path to the candidate JAR (must support --param-overrides <path>).
    Default: auto-detected from engine-uci/target/*-shaded.jar.

.PARAMETER Iterations
    Number of CLOP iterations. Default 300. Hard error if < 100.

.PARAMETER GamesPerIteration
    Games per candidate evaluation. Default 16.
    Hard error if > 50 unless --AllowSlowConfig is set.

.PARAMETER TimeControl
    cutechess-cli time control string. Default "10+0.1".
    Hard error if base > 30s or increment > 0.3s unless --AllowSlowConfig is set.

.PARAMETER CutechessPath
    Full path to cutechess-cli.exe (default "cutechess-cli").

.PARAMETER OpeningBook
    Path to opening book EPD (default: auto-detects noob_3moves.epd next to script).

.PARAMETER JavaPath
    Path to java executable (default: auto-resolved from JAVA env or PATH).

.PARAMETER OutputDir
    Directory for output files (default: same directory as this script).

.PARAMETER CsvFile
    Name of the results CSV file. Default: clop_results.csv.

.PARAMETER Concurrency
    cutechess-cli -concurrency value. Default 0 = auto-detect (max(2, logicalCores - 1)).

.PARAMETER AllowSlowConfig
    Bypasses GamesPerIteration > 50 and TC > 30+0.3 guardrails.
    FOR FINAL CONFIRMATION ONLY. Does not bypass Iterations < 100.

.EXAMPLE
    # Standard run (recommended)
    .\clop_tune.ps1

.EXAMPLE
    # Explicit settings
    .\clop_tune.ps1 `
        -Params              .\clop_params.json `
        -BaselineJar         .\baseline-v0.5.6-pretune.jar `
        -Iterations 500      -GamesPerIteration 16 `
        -TimeControl "10+0.1"

.EXAMPLE
    # Slow confirmation run after convergence
    .\clop_tune.ps1 -AllowSlowConfig -GamesPerIteration 100 -TimeControl "30+0.3" -Iterations 50
#>

param(
    [string] $Params              = "",   # default set below to PSScriptRoot-relative path
    [string] $BaselineJar         = "",   # default: tools/baseline-v0.5.6-pretune.jar
    [string] $CandidateJar        = "",   # default: auto-detect from engine-uci/target/*-shaded.jar
    [int]    $Iterations          = 300,
    [int]    $GamesPerIteration   = 16,
    [string] $TimeControl         = "10+0.1",
    [string] $CutechessPath       = "cutechess-cli",
    [string] $OpeningBook         = "",
    [string] $JavaPath            = "java",
    [string] $OutputDir           = $PSScriptRoot,
    [string] $CsvFile             = "clop_results.csv",
    [int]    $Concurrency         = 0,
    [switch] $AllowSlowConfig
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Resolve cutechess-cli: -CutechessPath takes precedence, then $env:CUTECHESS, then PATH.
if ($CutechessPath -eq "cutechess-cli") {
    if ($env:CUTECHESS -and (Test-Path $env:CUTECHESS)) {
        $CutechessPath = $env:CUTECHESS
    } else {
        $resolved = (Get-Command 'cutechess-cli' -ErrorAction SilentlyContinue)?.Source
        if ($resolved) { $CutechessPath = $resolved }
    }
}
if (-not (Test-Path $CutechessPath -ErrorAction SilentlyContinue) -and -not (Get-Command $CutechessPath -ErrorAction SilentlyContinue)) {
    Write-Error "[CLOP] cutechess-cli not found at '$CutechessPath'. Set `$env:CUTECHESS or add cutechess-cli.exe to PATH."
    exit 1
}

# -----------------------------------------------------------------------
# 1a. Apply defaults for paths that were not specified
# -----------------------------------------------------------------------
if ([string]::IsNullOrEmpty($Params)) {
    $Params = Join-Path $PSScriptRoot 'clop_params.json'
}
if ([string]::IsNullOrEmpty($BaselineJar)) {
    $BaselineJar = Join-Path $PSScriptRoot 'baseline-v0.5.6-pretune.jar'
}
if ([string]::IsNullOrEmpty($CandidateJar)) {
    $pattern = Join-Path $PSScriptRoot '..' 'engine-uci' 'target' '*-shaded.jar'
    $found = Get-Item $pattern -ErrorAction SilentlyContinue | Select-Object -Last 1
    if (-not $found) {
        Write-Error "[CLOP] Candidate JAR not found at '$pattern'. Run 'mvnw package -pl engine-uci -DskipTests' first."
        exit 1
    }
    $CandidateJar = $found.FullName
}

# -----------------------------------------------------------------------
# 1b. Validate baseline JAR exists (hard error — frozen baseline is mandatory)
# -----------------------------------------------------------------------
if (-not (Test-Path $BaselineJar)) {
    Write-Error "[CLOP] Baseline JAR not found: $BaselineJar`nThe baseline JAR must be a frozen build that never changes. Provide a valid --BaselineJar path."
    exit 1
}

# -----------------------------------------------------------------------
# 1c. Resolve absolute paths and guard against same-JAR self-play
# -----------------------------------------------------------------------
$BaselineJar  = (Resolve-Path $BaselineJar).Path
$CandidateJar = (Resolve-Path $CandidateJar).Path

if ($BaselineJar -eq $CandidateJar) {
    Write-Error "[CLOP] Baseline and candidate JARs are the same file ('$BaselineJar').`nSame-JAR self-play produces a flat win-rate surface regardless of parameter values.`nProvide a frozen baseline via --BaselineJar (default: tools/baseline-v0.5.6-pretune.jar)."
    exit 1
}

# -----------------------------------------------------------------------
# 1d. Helper: parse "tc=BASE+INC" or "BASE+INC" → @{base=float; inc=float}
# -----------------------------------------------------------------------
function Parse-TimeControl {
    param([string]$tc)
    $clean = $tc -replace '^tc=', ''
    if ($clean -match '^(\d+(?:\.\d+)?)\+(\d+(?:\.\d+)?)$') {
        return @{ base = [double]$Matches[1]; inc = [double]$Matches[2] }
    }
    return $null
}

$parsedTC = Parse-TimeControl $TimeControl
if ($null -eq $parsedTC) {
    Write-Error "[CLOP] Cannot parse TimeControl '$TimeControl'. Expected format: BASE+INC (e.g. 10+0.1)."
    exit 1
}
# Normalise: cutechess-cli -each requires the "tc=" prefix
if ($TimeControl -notmatch '^tc=') { $TimeControl = "tc=$TimeControl" }

# -----------------------------------------------------------------------
# 1e. Guardrails
#     --AllowSlowConfig bypasses GamesPerIteration and TC limits only.
#     Iterations < 100 is always a hard error (no bypass).
# -----------------------------------------------------------------------
if ($Iterations -lt 100) {
    Write-Error "[CLOP] Iterations=$Iterations is below the minimum of 100. The optimizer needs at least 100 iterations to explore the response surface. Set --Iterations 300 (default)."
    exit 1
}

if (-not $AllowSlowConfig) {
    if ($GamesPerIteration -gt 50) {
        Write-Error ("[CLOP] GamesPerIteration=$GamesPerIteration exceeds the CLOP limit of 50. " +
            "CLOP learns from many cheap samples — use 16 (default). Add --AllowSlowConfig to bypass (final confirmation only).")
        exit 1
    }
    if ($parsedTC.base -gt 30.0 -or $parsedTC.inc -gt 0.3) {
        Write-Error ("[CLOP] TimeControl '$TimeControl' exceeds the CLOP limit (base > 30s or inc > 0.3s). " +
            "Use default 10+0.1. Add --AllowSlowConfig to bypass (final confirmation only).")
        exit 1
    }
} else {
    Write-Warning "[CLOP] --AllowSlowConfig active — GamesPerIteration and TC guardrails bypassed."
}

# -----------------------------------------------------------------------
# 1f. Auto-detect concurrency
# -----------------------------------------------------------------------
if ($Concurrency -le 0) {
    $logicalCores = [System.Environment]::ProcessorCount
    $Concurrency  = [Math]::Min($GamesPerIteration, [Math]::Max(2, $logicalCores - 1))
}

# -----------------------------------------------------------------------
# 1g. Load and validate parameter definitions
# -----------------------------------------------------------------------
if (-not (Test-Path $Params)) {
    Write-Error "[CLOP] Params file not found: $Params"
    exit 1
}
$paramDefs = Get-Content $Params -Raw | ConvertFrom-Json
if ($null -eq $paramDefs -or $paramDefs.Count -eq 0) {
    Write-Error "[CLOP] Params file is empty or invalid JSON."
    exit 1
}
foreach ($p in $paramDefs) {
    if ([string]::IsNullOrEmpty($p.name)) { Write-Error "[CLOP] Each param entry must have a 'name' field."; exit 1 }
    if ($p.min -ge $p.max) { Write-Error "[CLOP] Parameter '$($p.name)': min must be < max."; exit 1 }
}

# -----------------------------------------------------------------------
# 2. Resolve output paths
# -----------------------------------------------------------------------
$overrideFile = Join-Path $OutputDir "eval_params_override.txt"
$csvPath      = Join-Path $OutputDir $CsvFile
$pgnPath      = Join-Path $OutputDir "clop_results.pgn"

# CSV header (write only if file does not exist)
if (-not (Test-Path $csvPath)) {
    $header = "iteration," + ($paramDefs | ForEach-Object { $_.name } | Join-String -Separator ",") + ",wins,draws,losses,elo_estimate,is_best"
    Set-Content $csvPath $header
}

# -----------------------------------------------------------------------
# 2b. Startup log
# -----------------------------------------------------------------------
$avgGameSec   = 2.0 * ($parsedTC.base + 80.0 * $parsedTC.inc)
$gamesPerHour = [Math]::Max(1, [int]($Concurrency * 3600.0 / $avgGameSec))
$totalGames   = $Iterations * $GamesPerIteration
$estMinutes   = [Math]::Round($totalGames / [double]$gamesPerHour * 60)

$paramRanges = ($paramDefs | ForEach-Object { "$($_.name) [$($_.min)..$($_.max)]" }) -join "  "

Write-Host "[CLOP] Baseline JAR : $BaselineJar"
Write-Host "[CLOP] Candidate JAR: $CandidateJar"
Write-Host "[CLOP] Parameters   : $paramRanges"
Write-Host "[CLOP] Iterations   : $Iterations  |  Games/iter: $GamesPerIteration  |  TC: $($TimeControl -replace '^tc=','')"
Write-Host "[CLOP] Concurrency  : $Concurrency"
Write-Host "[CLOP] Est. total games : $totalGames"
Write-Host "[CLOP] Est. wall time   : ~$estMinutes minutes"
if ($AllowSlowConfig) { Write-Host "[CLOP] *** --AllowSlowConfig active ***" }
Write-Host ""

# -----------------------------------------------------------------------
# 3. Helper: compute Elo from W/D/L
#    Formula: 400 * log10((W + 0.5*D) / (L + 0.5*D))
#    When W=0, use W_eff=0.5. When L=0, use L_eff=0.5 (floor to avoid log(0)).
# -----------------------------------------------------------------------
function Compute-Elo {
    param([int]$w, [int]$d, [int]$l)
    # Floor at 0.5 only when numerator or denominator would be exactly zero (W=0,D=0 or L=0,D=0)
    $wEff = [Math]::Max($w + $d / 2.0, 0.5)
    $lEff = [Math]::Max($l + $d / 2.0, 0.5)
    return [Math]::Round(400.0 * [Math]::Log10($wEff / $lEff), 1)
}

# -----------------------------------------------------------------------
# 4. Helper: write override file
#    Merges tuned values with any params already in the file that are NOT
#    being tuned this phase (e.g. ATK_WEIGHT_QUEEN locked from a prior phase).
# -----------------------------------------------------------------------
function Write-OverrideFile {
    param([hashtable]$values)
    $merged = [ordered]@{}
    if (Test-Path $overrideFile) {
        foreach ($line in (Get-Content $overrideFile)) {
            if ($line -match '^(\w+)=(.+)$') { $merged[$Matches[1]] = $Matches[2] }
        }
    }
    foreach ($k in $values.Keys) { $merged[$k] = $values[$k] }
    $lines = $merged.Keys | ForEach-Object { "$_=$($merged[$_])" }
    Set-Content $overrideFile $lines -Encoding UTF8
}

# -----------------------------------------------------------------------
# 5. Helper: Box-Muller Gaussian sample
# -----------------------------------------------------------------------
function Sample-Gaussian {
    param([double]$mean, [double]$sigma, [System.Random]$rng)
    $u1 = [Math]::Max($rng.NextDouble(), 1e-10)   # guard against log(0)
    $u2 = $rng.NextDouble()
    $z  = [Math]::Sqrt(-2.0 * [Math]::Log($u1)) * [Math]::Cos(2.0 * [Math]::PI * $u2)
    return $mean + $sigma * $z
}

# -----------------------------------------------------------------------
# 6. Helper: run cutechess-cli and parse W/D/L
#    Candidate receives --param-overrides <file>; baseline receives nothing.
# -----------------------------------------------------------------------
function Run-Match {
    param([hashtable]$candidateValues)

    Write-OverrideFile $candidateValues

    # Resolve java executable — avoid JAVA_HOME paths with spaces that break cutechess cmd=
    $java = if ($JavaPath -ne 'java') {
        $JavaPath
    } elseif ($env:JAVA -and (Test-Path (Join-Path $env:JAVA 'bin\java.exe'))) {
        Join-Path $env:JAVA 'bin\java.exe'
    } else {
        'java'
    }

    # Auto-detect opening book if not specified
    $book = $OpeningBook
    if ([string]::IsNullOrEmpty($book)) {
        $defaultBook = Join-Path $PSScriptRoot 'noob_3moves.epd'
        if (Test-Path $defaultBook) { $book = $defaultBook }
    }

    $cutechessArgs = @(
        "-engine", "name=Vex-candidate", "cmd=$java", "arg=-jar", "arg=$CandidateJar",
                   "arg=--param-overrides", "arg=$overrideFile", "proto=uci",
        "-engine", "name=Vex-baseline",  "cmd=$java", "arg=-jar", "arg=$BaselineJar", "proto=uci",
        "-each", $TimeControl,
        "-games", $GamesPerIteration,
        "-repeat",
        "-recover",
        "-resign",  "movecount=5", "score=600",
        "-draw",    "movenumber=40", "movecount=8", "score=10",
        "-concurrency", $Concurrency,
        "-pgnout", $pgnPath
    )

    if (-not [string]::IsNullOrEmpty($book) -and (Test-Path $book)) {
        $cutechessArgs += @("-openings", "file=$book", "format=epd", "order=random", "plies=4")
    }

    $output = & $CutechessPath @cutechessArgs 2>&1 | Out-String

    # Parse last "Score of X vs Y: W - D - L" line
    $matchResult = ([regex]"Score of .+?: (\d+) - (\d+) - (\d+)").Matches($output) | Select-Object -Last 1
    if ($matchResult) {
        $g = $matchResult.Groups
        return @{ wins = [int]$g[1].Value; draws = [int]$g[2].Value; losses = [int]$g[3].Value }
    }
    Write-Warning "[CLOP] Could not parse W/D/L from cutechess output:`n$output"
    return @{ wins = 0; draws = $GamesPerIteration; losses = 0 }
}

# -----------------------------------------------------------------------
# 6. CLOP main loop
# -----------------------------------------------------------------------

# Initialise current best from param definitions
$bestValues = @{}
foreach ($p in $paramDefs) { $bestValues[$p.name] = [int]$p.current }
$bestElo  = $null
$bestIter = 0

# Single RNG instance — hoisted so each parameter gets independent draws.
# Creating [Random]::new() inside the foreach would re-use the same TickCount
# seed for every parameter in the same millisecond, collapsing all
# displacements onto the diagonal and degrading exploration.
$rng = [System.Random]::new()

for ($iter = 1; $iter -le $Iterations; $iter++) {

    # --- Sample candidate values ---
    $candidate = @{}
    if ($iter -eq 1) {
        # Iteration 1: use current (JSON "current") values exactly — anchors the surface
        foreach ($p in $paramDefs) { $candidate[$p.name] = [int]$p.current }
    } else {
        # Iterations 2+: Box-Muller Gaussian centred on current best, σ = (max-min)/6
        foreach ($p in $paramDefs) {
            $sigma    = ($p.max - $p.min) / 6.0
            $raw      = Sample-Gaussian -mean $bestValues[$p.name] -sigma $sigma -rng $rng
            $step     = [int]$p.step
            $snapped  = [int][Math]::Round($raw / $step) * $step
            $clamped  = [Math]::Max([int]$p.min, [Math]::Min([int]$p.max, $snapped))
            $candidate[$p.name] = $clamped
        }
    }

    $paramStr = ($paramDefs | ForEach-Object { "$($_.name)=$($candidate[$_.name])" }) -join " "
    $iterPad  = $iter.ToString().PadLeft(($Iterations.ToString().Length))

    # --- Run match ---
    $result = Run-Match $candidate
    $w = $result.wins; $d = $result.draws; $l = $result.losses
    $elo = Compute-Elo $w $d $l

    # Format Elo with explicit sign, e.g. +18.4 or -3.2
    $eloSign = if ($elo -ge 0) { '+' } else { '' }
    $eloStr  = "${eloSign}$elo"

    # --- Update best ---
    $isBest = 0
    if ($null -eq $bestElo -or $elo -gt $bestElo) {
        $bestElo   = $elo
        $bestIter  = $iter
        $bestValues = $candidate.Clone()
        $isBest    = 1
    }

    $bestSign = if ($bestElo -ge 0) { '+' } else { '' }
    $bestStr  = "${bestSign}$bestElo @ iter $bestIter"

    Write-Host "[CLOP] Iter $iterPad/$Iterations | $paramStr | W:$w D:$d L:$l | Elo: $eloStr | Best: $bestStr"

    # --- Append CSV row ---
    $valueFields = ($paramDefs | ForEach-Object { $candidate[$_.name] }) -join ","
    Add-Content $csvPath "$iter,$valueFields,$w,$d,$l,$elo,$isBest"
}

# -----------------------------------------------------------------------
# 7. Summary
# -----------------------------------------------------------------------
Write-Host ""
Write-Host "[CLOP] Run complete."
Write-Host "[CLOP]   Iterations      : $Iterations"
Write-Host "[CLOP]   Best Elo        : $(if ($bestElo -ge 0) { '+' } else { '' })$bestElo  (iter $bestIter)"
Write-Host "[CLOP]   Best params     :"
foreach ($k in ($paramDefs | ForEach-Object { $_.name })) {
    Write-Host "[CLOP]     $k = $($bestValues[$k])"
}
Write-Host "[CLOP]   Override file   : $overrideFile"
Write-Host "[CLOP]   Results CSV     : $csvPath"
Write-Host "[CLOP]   PGN archive     : $pgnPath"
Write-Host ""

# Write best params to override file for downstream SPRT / bake step
Write-OverrideFile $bestValues
