#Requires -Version 5.1
<#
.SYNOPSIS
    Pre-submission verification checklist for the Vex chess engine (CCRL).

.DESCRIPTION
    Locates the fat JAR, then runs four checks:
      1. JAR existence
      2. UCI handshake — confirms 'uciok' in response to 'uci'
      3. isready handshake — confirms 'readyok' in response to 'isready'
      4. Bench determinism — runs 'bench' twice and asserts node-count lines are identical

    Prints PASS or FAIL for each check and exits with code 0 (all pass) or 1 (any fail).

.PARAMETER JarPath
    Explicit path to the engine-uci fat JAR. If omitted the script auto-detects the
    newest engine-uci-*.jar (excluding original-*) in engine-uci/target/.

.PARAMETER BenchDepth
    Depth passed to the 'bench' command. Default: 13.

.PARAMETER TimeoutSeconds
    Seconds to wait for each engine response before declaring a timeout. Default: 120.
#>
param(
    [string] $JarPath        = "",
    [int]    $BenchDepth     = 13,
    [int]    $TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

function Pass {
    param([string]$Label)
    Write-Host "  [PASS] $Label" -ForegroundColor Green
}

function Fail {
    param([string]$Label, [string]$Detail = "")
    if ($Detail) {
        Write-Host "  [FAIL] $Label — $Detail" -ForegroundColor Red
    } else {
        Write-Host "  [FAIL] $Label" -ForegroundColor Red
    }
    $Script:AnyFailed = $true
}

# Send one or more lines to a process's stdin and collect stdout until an
# expected token appears (or timeout expires). Returns the collected output.
function Invoke-EngineLines {
    param(
        [System.Diagnostics.Process]$Proc,
        [string[]] $InputLines,
        [string]   $WaitForToken,
        [int]      $TimeoutMs = 30000
    )
    foreach ($l in $InputLines) {
        $Proc.StandardInput.WriteLine($l)
    }
    $Proc.StandardInput.Flush()

    $collected = [System.Collections.Generic.List[string]]::new()
    $deadline  = [System.Diagnostics.Stopwatch]::StartNew()

    while ($deadline.ElapsedMilliseconds -lt $TimeoutMs) {
        if ($Proc.StandardOutput.EndOfStream) { break }
        $line = $Proc.StandardOutput.ReadLine()
        if ($null -ne $line) {
            $collected.Add($line)
            if ($line -like "*$WaitForToken*") { break }
        }
    }
    return $collected
}

# ---------------------------------------------------------------------------
# Locate JAR
# ---------------------------------------------------------------------------

$Script:AnyFailed = $false

$repoRoot = Split-Path -Parent $PSScriptRoot

Write-Host "`nVex — CCRL Pre-Submission Checklist" -ForegroundColor Cyan
Write-Host "====================================`n"

# CHECK 1 — JAR exists
Write-Host "Check 1: Fat JAR exists"
if (-not $JarPath) {
    $targetDir = Join-Path $repoRoot "engine-uci" "target"
    $candidates = Get-ChildItem -Path $targetDir -Filter "engine-uci-*.jar" -ErrorAction SilentlyContinue |
                  Where-Object { $_.Name -notlike "original-*" } |
                  Sort-Object LastWriteTime -Descending
    if ($candidates.Count -gt 0) {
        $JarPath = $candidates[0].FullName
    }
}

if (-not $JarPath -or -not (Test-Path $JarPath)) {
    Fail "Fat JAR exists" "not found — run 'mvnw package -pl engine-uci -am' first"
    Write-Host "`nCannot continue without the JAR. Aborting.`n" -ForegroundColor Yellow
    exit 1
} else {
    Pass "Fat JAR exists: $(Split-Path -Leaf $JarPath)"
}

# ---------------------------------------------------------------------------
# Launch engine process (shared across checks 2–4)
# ---------------------------------------------------------------------------

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName               = "java"
$psi.Arguments              = "-jar `"$JarPath`""
$psi.RedirectStandardInput  = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError  = $true
$psi.UseShellExecute        = $false
$psi.CreateNoWindow         = $true

$proc = [System.Diagnostics.Process]::new()
$proc.StartInfo = $psi
$proc.Start() | Out-Null

# Discard stderr asynchronously to prevent the pipe from blocking.
$proc.BeginErrorReadLine()

# ---------------------------------------------------------------------------
# CHECK 2 — UCI handshake
# ---------------------------------------------------------------------------

Write-Host "`nCheck 2: UCI handshake (uci → uciok)"

$uciLines = Invoke-EngineLines -Proc $proc -InputLines @("uci") -WaitForToken "uciok" -TimeoutMs ($TimeoutSeconds * 1000)
if ($uciLines -contains "uciok") {
    Pass "UCI handshake"
} else {
    Fail "UCI handshake" "did not receive 'uciok' within ${TimeoutSeconds}s. Got: $($uciLines -join '; ')"
}

# ---------------------------------------------------------------------------
# CHECK 3 — isready handshake
# ---------------------------------------------------------------------------

Write-Host "`nCheck 3: isready handshake (isready → readyok)"

$readyLines = Invoke-EngineLines -Proc $proc -InputLines @("isready") -WaitForToken "readyok" -TimeoutMs ($TimeoutSeconds * 1000)
if ($readyLines -contains "readyok") {
    Pass "isready handshake"
} else {
    Fail "isready handshake" "did not receive 'readyok' within ${TimeoutSeconds}s"
}

# ---------------------------------------------------------------------------
# CHECK 4 — Bench determinism
# Bench outputs many 'info' lines followed by a summary. The key deterministic
# field is the total node count on the final 'Nodes searched : NNN' line.
# We run bench twice via two consecutive 'bench N' UCI commands within the
# same process to avoid JVM warmup noise distorting the comparison.
# ---------------------------------------------------------------------------

Write-Host "`nCheck 4: bench determinism (two runs at depth $BenchDepth)"

function Get-BenchNodeLine {
    param([string[]] $Lines)
    # Bench summary line format: "Bench: N nodes NNNms N nps | q_ratio=..."
    $Lines | Where-Object { $_ -match "^Bench:\s+\d+ nodes" } | Select-Object -Last 1
}

$bench1Lines = Invoke-EngineLines -Proc $proc `
    -InputLines @("bench $BenchDepth") `
    -WaitForToken "Bench:" `
    -TimeoutMs ($TimeoutSeconds * 1000)

$bench1NodeLine = Get-BenchNodeLine $bench1Lines

# Brief pause between runs so any async output from run 1 drains.
Start-Sleep -Milliseconds 500

$bench2Lines = Invoke-EngineLines -Proc $proc `
    -InputLines @("bench $BenchDepth") `
    -WaitForToken "Bench:" `
    -TimeoutMs ($TimeoutSeconds * 1000)

$bench2NodeLine = Get-BenchNodeLine $bench2Lines

function Extract-NodeCount {
    param([string] $BenchLine)
    if ($BenchLine -match "Bench:\s+(\d+)\s+nodes") { return $Matches[1] }
    return $null
}

$nodes1 = Extract-NodeCount $bench1NodeLine
$nodes2 = Extract-NodeCount $bench2NodeLine

if (-not $bench1NodeLine -or -not $nodes1) {
    Fail "bench determinism" "run 1 produced no node-count line"
} elseif (-not $bench2NodeLine -or -not $nodes2) {
    Fail "bench determinism" "run 2 produced no node-count line"
} elseif ($nodes1 -eq $nodes2) {
    Pass "bench determinism: $nodes1 nodes (both runs identical)"
} else {
    Fail "bench determinism" "node counts differ`n    Run 1: $nodes1 nodes ($bench1NodeLine)`n    Run 2: $nodes2 nodes ($bench2NodeLine)"
}

# ---------------------------------------------------------------------------
# Shut down engine
# ---------------------------------------------------------------------------

try {
    $proc.StandardInput.WriteLine("quit")
    $proc.StandardInput.Flush()
    $proc.WaitForExit(5000) | Out-Null
    if (-not $proc.HasExited) { $proc.Kill() }
} catch {
    # Engine already exited — ignore.
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

Write-Host "`n===================================="
if ($Script:AnyFailed) {
    Write-Host "Result: FAIL — one or more checks did not pass." -ForegroundColor Red
    exit 1
} else {
    Write-Host "Result: PASS — all checks passed." -ForegroundColor Green
    exit 0
}
