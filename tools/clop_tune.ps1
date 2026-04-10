<#
.SYNOPSIS
    CLOP (Confident Local Optimization by Pair-comparisons) tuning loop.

.DESCRIPTION
    Tunes evaluation parameters by running engine-vs-engine matches.  Each iteration:
      1. Samples a candidate parameter vector (Gaussian around current best).
      2. Writes the vector to a temp override file.
      3. Runs cutechess-cli between candidate and baseline.
      4. Parses W/D/L and computes a noisy Elo estimate.
      5. Updates the best vector if candidate is better.
      6. Appends a row to clop_results.csv.

    DESIGN PRINCIPLE — why CLOP uses short, noisy iterations
    ---------------------------------------------------------
    CLOP is a response-surface optimizer, not a statistical hypothesis test.
    It needs many cheap samples spread across the parameter space to estimate
    the gradient, not a handful of high-confidence W/D/L counts per point.

    A run with 300 iterations of 16 games each (4,800 games total) learns the
    response surface far better than 50 iterations of 200 games (10,000 games)
    even though it plays fewer total games, because the optimizer gets 6x more
    gradient steps.  Each noisy observation still moves the estimate in the
    right direction on average; the noise averages out over iterations.

    Do NOT increase Games to "improve accuracy".  That is the wrong tradeoff
    for CLOP.  Use more iterations instead.  Do NOT treat CLOP like SPRT —
    they solve different problems.  SPRT tests a fixed hypothesis; CLOP searches
    for a better parameter vector.

    If you need confirmation that the best parameters found by CLOP actually
    improve the engine, run an SPRT after CLOP, not a slow CLOP.

    SLOW MODE (--SlowMode)
    ----------------------
    The -SlowMode switch bypasses all guardrails and allows long time controls
    and large game batches.  It exists solely for final confirmation runs after
    CLOP has converged.  Do not use it for normal tuning.

.PARAMETER Params
    Path to a JSON file describing parameters.  Each entry must have:
    { "name": "TEMPO", "current": 21, "min": 5, "max": 40, "step": 1 }

.PARAMETER Games
    Games per candidate evaluation.  Default 16.
    CLOP learns best from many cheap samples — keep this LOW (10–20).
    Hard error if > 50 unless -SlowMode is set.

.PARAMETER Iterations
    Number of CLOP iterations.  Default 300.
    Warning if < 100 (too few for the optimizer to explore the surface).

.PARAMETER BaselineJar
    Path to the fixed baseline JAR.

.PARAMETER CandidateJar
    Path to the candidate JAR (must support --param-overrides <path>).

.PARAMETER TimeControl
    cutechess-cli time control string.  Default "tc=1+0.01".
    Hard error if base time > 3s or increment > 0.03s unless -SlowMode is set.

.PARAMETER CutechessPath
    Full path to cutechess-cli.exe (default "cutechess-cli").

.PARAMETER OpeningBook
    Path to opening book EPD (default: auto-detects noob_3moves.epd next to script).

.PARAMETER JavaPath
    Path to java executable (default: auto-resolved from JAVA_HOME or JAVA env).

.PARAMETER OutputDir
    Directory for output files (default: same directory as this script).

.PARAMETER Concurrency
    cutechess-cli -concurrency value.  Default 0 = auto-detect from logical cores.
    Auto formula: max(2, logicalCores - 1).

.PARAMETER SlowMode
    Bypass all guardrails.  Allows Games > 50 and TC > 3+0.03.
    FOR FINAL CONFIRMATION ONLY.  Do not use for normal CLOP tuning.

.EXAMPLE
    # Normal fast-noisy run (recommended)
    .\clop_tune.ps1 `
        -Params       .\clop_params.json `
        -BaselineJar  .\engine-uci-baseline.jar `
        -CandidateJar .\engine-uci-candidate.jar

.EXAMPLE
    # Explicit settings
    .\clop_tune.ps1 `
        -Params       .\clop_params.json `
        -BaselineJar  .\engine-uci-baseline.jar `
        -CandidateJar .\engine-uci-candidate.jar `
        -Games 16 -Iterations 500 -TimeControl "tc=2+0.02"

.EXAMPLE
    # Slow confirmation run — NOT for tuning
    .\clop_tune.ps1 `
        -Params       .\clop_params.json `
        -BaselineJar  .\engine-uci-baseline.jar `
        -CandidateJar .\engine-uci-candidate.jar `
        -SlowMode -Games 200 -TimeControl "tc=10+0.1" -Iterations 50
#>

param(
    [Parameter(Mandatory)] [string] $Params,
    [int]    $Games         = 16,
    [int]    $Iterations    = 300,
    [Parameter(Mandatory)] [string] $BaselineJar,
    [Parameter(Mandatory)] [string] $CandidateJar,
    [string] $TimeControl   = "tc=1+0.01",
    [string] $CutechessPath = "cutechess-cli",
    [string] $OpeningBook   = "",
    [string] $JavaPath      = "java",
    [string] $OutputDir     = $PSScriptRoot,
    [int]    $Concurrency   = 0,   # 0 = auto-detect
    [switch] $SlowMode             # bypass guardrails; for final confirmation only
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# -----------------------------------------------------------------------
# 1a. Helper: parse "tc=BASE+INC" or "BASE+INC" → @{base=float; inc=float}
#     Returns $null if the format is not recognised.
# -----------------------------------------------------------------------
function Parse-TimeControl {
    param([string]$tc)
    $clean = $tc -replace '^tc=', ''
    if ($clean -match '^(\d+(?:\.\d+)?)\+(\d+(?:\.\d+)?)$') {
        return @{ base = [double]$Matches[1]; inc = [double]$Matches[2] }
    }
    return $null
}

# -----------------------------------------------------------------------
# 1b. Guardrails — reject configs that are wrong for CLOP
#
#     CLOP is a noisy response-surface optimizer, not a hypothesis test.
#     Many cheap samples beat a few expensive ones.  Default limits:
#       Games  ≤ 50   — more games per iteration is counterproductive
#       TC base ≤ 3s  — long TCs slow down iteration without helping CLOP
#     -SlowMode bypasses all guardrails for final confirmation runs only.
# -----------------------------------------------------------------------
$parsedTC = Parse-TimeControl $TimeControl
if ($null -eq $parsedTC) {
    Write-Error "Cannot parse TimeControl '$TimeControl'.  Expected format: tc=BASE+INC (e.g. tc=1+0.01)."
    exit 1
}

if (-not $SlowMode) {
    if ($Games -gt 50) {
        Write-Error ("Games=$Games exceeds the CLOP fast-tuning limit of 50. " +
            "CLOP learns from many cheap samples, not a few precise ones. " +
            "Use -Games 16 (recommended) or add -SlowMode to bypass this check.")
        exit 1
    }
    if ($parsedTC.base -gt 3.0 -or $parsedTC.inc -gt 0.03) {
        Write-Error ("TimeControl '$TimeControl' exceeds the CLOP fast-tuning limit (base > 3s or inc > 0.03s). " +
            "Use a short TC such as 'tc=1+0.01' or 'tc=2+0.02'. " +
            "Add -SlowMode to bypass — but only for final confirmation, not for tuning.")
        exit 1
    }
    if ($Iterations -lt 100) {
        Write-Warning ("Iterations=$Iterations is very low for CLOP. " +
            "The optimizer needs at least 100 iterations to explore the response surface. " +
            "Consider -Iterations 300 (default).")
    }
} else {
    Write-Warning "SLOW MODE active — guardrails bypassed.  Intended for final confirmation only."
}

# -----------------------------------------------------------------------
# 1c. Auto-detect concurrency if not specified
#     Formula: max(2, logicalCores - 1) so at least one core stays free
#     for the OS and cutechess bookkeeping.  Capped at Games to avoid
#     spinning up more worker slots than there are games to run.
# -----------------------------------------------------------------------
if ($Concurrency -le 0) {
    $logicalCores = [System.Environment]::ProcessorCount
    $Concurrency  = [Math]::Min($Games, [Math]::Max(2, $logicalCores - 1))
}

# -----------------------------------------------------------------------
# 1d. Load and validate parameter definitions
# -----------------------------------------------------------------------
if (-not (Test-Path $Params)) {
    Write-Error "Params file not found: $Params"
    exit 1
}
$paramDefs = Get-Content $Params -Raw | ConvertFrom-Json
if ($null -eq $paramDefs -or $paramDefs.Count -eq 0) {
    Write-Error "Params file is empty or invalid JSON."
    exit 1
}
foreach ($p in $paramDefs) {
    if ([string]::IsNullOrEmpty($p.name)) { Write-Error "Each param entry must have a 'name' field."; exit 1 }
    if ($p.min -ge $p.max) { Write-Error "Parameter '$($p.name)': min must be < max."; exit 1 }
}

# -----------------------------------------------------------------------
# 2. Resolve output paths
# -----------------------------------------------------------------------
$overrideFile  = Join-Path $OutputDir "eval_params_override.txt"
$csvPath       = Join-Path $OutputDir "clop_results.csv"
$BaselineJar   = (Resolve-Path $BaselineJar).Path
$CandidateJar  = (Resolve-Path $CandidateJar).Path

# CSV header
if (-not (Test-Path $csvPath)) {
    $header = "iteration," + ($paramDefs | ForEach-Object { $_.name } | Join-String -Separator ",") + ",wins,draws,losses,elo,best_elo"
    Set-Content $csvPath $header
}

# -----------------------------------------------------------------------
# 2b. Startup log — print estimated runtime before touching cutechess
#
#     Estimate: avg game duration = 2 * (base + avgHalfMoves * inc)
#     where avgHalfMoves ≈ 80 (40 moves × 2 sides).
#     With -concurrency C, throughput ≈ C games / avgGameSec.
# -----------------------------------------------------------------------
$avgGameSec    = 2.0 * ($parsedTC.base + 80.0 * $parsedTC.inc)
$gamesPerHour  = [Math]::Max(1, [int]($Concurrency * 3600.0 / $avgGameSec))
$totalGames    = $Iterations * $Games
$estHours      = $totalGames / [double]$gamesPerHour
$estMinutes    = [Math]::Round($estHours * 60)

Write-Host "=== CLOP tuning startup ==="
Write-Host "  Params file      : $Params ($($paramDefs.Count) parameters)"
Write-Host "  Games / iter     : $Games"
Write-Host "  Iterations       : $Iterations"
Write-Host "  Time control     : $TimeControl"
Write-Host "  Concurrency      : $Concurrency"
Write-Host "  Total games      : $totalGames"
Write-Host "  Est. games/hr    : ~$gamesPerHour"
Write-Host "  Est. wall time   : ~$estMinutes min (~$([Math]::Round($estHours,1)) hr)"
if ($SlowMode) { Write-Host "  *** SLOW MODE *** guardrails bypassed" }
Write-Host "  Candidate JAR    : $CandidateJar"
Write-Host "  Baseline JAR     : $BaselineJar"
Write-Host "  Results CSV      : $csvPath"
Write-Host ""

# -----------------------------------------------------------------------
# 3. Helper: compute Elo from W/D/L
#    Uses W + D/2 vs D/2 + L to avoid zero-denominator for draws.
# -----------------------------------------------------------------------
function Compute-Elo {
    param([int]$w, [int]$d, [int]$l)
    $effectiveWins   = $w + $d / 2.0
    $effectiveLosses = $l + $d / 2.0
    if ($effectiveWins -le 0 -or $effectiveLosses -le 0) { return $null }
    return [Math]::Round(400 * [Math]::Log10($effectiveWins / $effectiveLosses), 2)
}

# -----------------------------------------------------------------------
# 4. Helper: write override file
# -----------------------------------------------------------------------
function Write-OverrideFile {
    param([hashtable]$values)
    $lines = $values.Keys | Sort-Object | ForEach-Object { "$_=$($values[$_])" }
    Set-Content $overrideFile ($lines -join "`n")
}

# -----------------------------------------------------------------------
# 5. Helper: run cutechess-cli and parse W/D/L
# -----------------------------------------------------------------------
function Run-Match {
    param([hashtable]$candidateValues)

    Write-OverrideFile $candidateValues

    # Resolve java executable (same logic as sprt.ps1)
    $java = if ($env:JAVA) { $env:JAVA } elseif ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

    # Auto-detect opening book if not specified (same logic as sprt.ps1)
    $book = $OpeningBook
    if ([string]::IsNullOrEmpty($book)) {
        $defaultBook = Join-Path $PSScriptRoot 'noob_3moves.epd'
        if (Test-Path $defaultBook) { $book = $defaultBook }
    }

    # Each key=value must be a separate argument for cutechess-cli
    $cutechessArgs = @(
        "-engine", "name=Vex-candidate", "cmd=$java", "arg=-jar", "arg=$CandidateJar", "arg=--param-overrides", "arg=$overrideFile", "proto=uci",
        "-engine", "name=Vex-baseline",  "cmd=$java", "arg=-jar", "arg=$BaselineJar",  "proto=uci",
        "-each", $TimeControl,
        "-games", $Games,
        "-repeat",
        "-recover",
        "-resign",  "movecount=5", "score=600",
        "-draw",    "movenumber=40", "movecount=8", "score=10",
        "-concurrency", $Concurrency
    )

    if (-not [string]::IsNullOrEmpty($book) -and (Test-Path $book)) {
        $cutechessArgs += @("-openings", "file=$book", "format=epd", "order=random", "plies=4")
    }

    $output = & $CutechessPath @cutechessArgs 2>&1 | Out-String

    # Parse last "Score of X vs Y: W - D - L  [...]" line (intermediate lines possible with -ratinginterval)
    $matchResult = ([regex]"Score of .+?: (\d+) - (\d+) - (\d+)").Matches($output) | Select-Object -Last 1
    if ($matchResult) {
        $g = $matchResult.Groups
        return @{
            wins   = [int]$g[1].Value
            draws  = [int]$g[2].Value
            losses = [int]$g[3].Value
        }
    }
    # Legacy fallback (single match)
    if ($output -match "Score of .+?: (\d+) - (\d+) - (\d+)") {
        return @{
            wins   = [int]$Matches[1]
            draws  = [int]$Matches[2]
            losses = [int]$Matches[3]
        }
    }

    Write-Warning "Could not parse W/D/L from cutechess output:`n$output"
    return @{ wins = 0; draws = $Games; losses = 0 }
}

# -----------------------------------------------------------------------
# 6. CLOP main loop
# -----------------------------------------------------------------------

# Initialise current best from param definitions
$bestValues = @{}
foreach ($p in $paramDefs) { $bestValues[$p.name] = [int]$p.current }
$bestElo = $null

for ($iter = 1; $iter -le $Iterations; $iter++) {

    # --- Sample candidate values ---
    $candidate = @{}
    if ($iter -eq 1) {
        # First iteration: use current (baseline) values
        foreach ($p in $paramDefs) { $candidate[$p.name] = [int]$p.current }
    } else {
        # Gaussian sample around current best, std = (max - min) / 6
        foreach ($p in $paramDefs) {
            $std   = ($p.max - $p.min) / 6.0
            $raw   = $bestValues[$p.name] + [Random]::new().NextDouble() * $std * 2 - $std
            $step  = [int]$p.step
            $snapped = [int][Math]::Round($raw / $step) * $step
            $clamped = [Math]::Max($p.min, [Math]::Min($p.max, $snapped))
            $candidate[$p.name] = [int]$clamped
        }
    }

    $paramStr = ($candidate.Keys | Sort-Object | ForEach-Object { "$_=$($candidate[$_])" }) -join " "
    Write-Host "[$iter/$Iterations] Testing: $paramStr"

    # --- Run match ---
    $result = Run-Match $candidate
    $w = $result.wins; $d = $result.draws; $l = $result.losses
    $elo = Compute-Elo $w $d $l

    $eloStr = if ($null -eq $elo) { "N/A" } else { $elo.ToString() }
    Write-Host "  W=$w D=$d L=$l  Elo=$eloStr  (best so far: $(if ($null -eq $bestElo) { 'N/A' } else { $bestElo }))"

    # --- Update best ---
    if ($null -ne $elo -and ($null -eq $bestElo -or $elo -gt $bestElo)) {
        $bestElo = $elo
        $bestValues = $candidate.Clone()
        Write-Host "  --> New best! Elo=$bestElo"
    }

    # --- Log to CSV ---
    $valueFields = ($paramDefs | ForEach-Object { $candidate[$_.name] }) -join ","
    $row = "$iter,$valueFields,$w,$d,$l,$eloStr,$(if ($null -eq $bestElo) { 'N/A' } else { $bestElo })"
    Add-Content $csvPath $row
}

# -----------------------------------------------------------------------
# 7. Summary
# -----------------------------------------------------------------------
Write-Host ""
Write-Host "=== CLOP complete ==="
Write-Host "Best Elo : $bestElo"
Write-Host "Best params:"
foreach ($k in $bestValues.Keys | Sort-Object) {
    Write-Host "  $k = $($bestValues[$k])"
}
Write-Host ""
Write-Host "Override file written to : $overrideFile"
Write-Host "Full results in          : $csvPath"

# Write best params to override file for downstream use
Write-OverrideFile $bestValues
