<#
.SYNOPSIS
    Simplified CLOP (Confident Local Optimization by Pair-comparisons) tuning loop.

.DESCRIPTION
    Tunes evaluation parameters by running engine-vs-engine matches.  Each iteration:
      1. Samples a candidate parameter vector (Gaussian around current best).
      2. Writes the vector to a temp override file.
      3. Runs cutechess-cli between candidate and baseline.
      4. Parses W/D/L and computes an Elo estimate.
      5. Updates the best vector if candidate is better.
      6. Appends a row to clop_results.csv.

.PARAMETER Params
    Path to a JSON file describing parameters.  Each entry must have:
    { "name": "TEMPO", "current": 21, "min": 5, "max": 40, "step": 1 }

.PARAMETER Games
    Number of games per candidate evaluation (default 200).

.PARAMETER Iterations
    Number of CLOP iterations (default 50).

.PARAMETER BaselineJar
    Path to the fixed baseline JAR.

.PARAMETER CandidateJar
    Path to the candidate JAR (must support --param-overrides <path>).

.PARAMETER TimeControl
    cutechess-cli time control string (default "tc=10+0.1").

.PARAMETER CutechessPath
    Full path to cutechess-cli.exe (default "cutechess-cli").

.PARAMETER OpeningBook
    Path to opening book .bin (default empty = no book).

.PARAMETER JavaPath
    Path to java executable (default "java").

.PARAMETER OutputDir
    Directory for output files (default: same directory as this script).

.EXAMPLE
    .\clop_tune.ps1 `
        -Params .\clop_params.json `
        -BaselineJar .\engine-uci-0.4.9.jar `
        -CandidateJar ..\engine-uci\target\engine-uci-0.5.6-SNAPSHOT-shaded.jar `
        -Games 200 -Iterations 50
#>

param(
    [Parameter(Mandatory)] [string] $Params,
    [int]    $Games         = 200,
    [int]    $Iterations    = 50,
    [Parameter(Mandatory)] [string] $BaselineJar,
    [Parameter(Mandatory)] [string] $CandidateJar,
    [string] $TimeControl   = "tc=10+0.1",
    [string] $CutechessPath = "cutechess-cli",
    [string] $OpeningBook   = "",
    [string] $JavaPath      = "java",
    [string] $OutputDir     = $PSScriptRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# -----------------------------------------------------------------------
# 1. Load and validate parameter definitions
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

    # Build engine arguments
    $candidateEngineArgs = "cmd=`"$JavaPath`" arg=`"-jar`" arg=`"$CandidateJar`" arg=`"--param-overrides`" arg=`"$overrideFile`" proto=uci"
    $baselineEngineArgs  = "cmd=`"$JavaPath`" arg=`"-jar`" arg=`"$BaselineJar`" proto=uci"

    $cutechessArgs = @(
        "-engine", $candidateEngineArgs,
        "-engine", $baselineEngineArgs,
        "-each", $TimeControl,
        "-games", $Games,
        "-repeat"
    )

    if (-not [string]::IsNullOrEmpty($OpeningBook) -and (Test-Path $OpeningBook)) {
        $cutechessArgs += @("-openings", "file=`"$OpeningBook`"", "format=bin", "order=random")
    }

    $output = & $CutechessPath @cutechessArgs 2>&1 | Out-String

    # Parse "Score of X vs Y: W - D - L  [...]"
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

Write-Host "=== CLOP tuning: $($paramDefs.Count) params, $Iterations iterations, $Games games each ==="
Write-Host "Candidate JAR : $CandidateJar"
Write-Host "Baseline JAR  : $BaselineJar"
Write-Host "Results CSV   : $csvPath"
Write-Host ""

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
