<#
.SYNOPSIS
    Annotates an EPD corpus with Stockfish static eval scores.

.DESCRIPTION
    For each FEN in the input EPD file, sends the position to a Stockfish
    process and reads the static eval via the "eval" command (not "go depth N").
    Outputs one "<FEN 6-field> <cp_int>" line per annotated position.

    Mate scores and parse failures are silently skipped.
    Progress is printed every 10,000 positions.

    Output format is compatible with engine-tuner --label-mode eval.

.PARAMETER InputEpd
    Path to the input EPD file (quiet-labeled.epd or similar).

.PARAMETER Output
    Path to the output annotation file ("<FEN> <cp_int>" per line).

.PARAMETER Depth
    Reserved for future use (currently ignored; eval uses Stockfish static eval).
    Default: 0.

.PARAMETER StockfishPath
    Full path to the Stockfish executable.

.EXAMPLE
    .\annotate_corpus.ps1 `
        -InputEpd tools\quiet-labeled.epd `
        -Output tools\sf-eval-corpus.txt `
        -StockfishPath "C:\Tools\stockfish-18\stockfish-windows-x86-64-avx2.exe"
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$InputEpd,

    [Parameter(Mandatory = $true)]
    [string]$Output,

    [int]$Depth = 0,

    [Parameter(Mandatory = $true)]
    [string]$StockfishPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Validate inputs
# ---------------------------------------------------------------------------
if (-not (Test-Path $InputEpd)) {
    Write-Error "Input EPD not found: $InputEpd"
    exit 1
}
if (-not (Test-Path $StockfishPath)) {
    Write-Error "Stockfish executable not found: $StockfishPath"
    exit 1
}

# ---------------------------------------------------------------------------
# Start Stockfish process with redirected stdin/stdout
# ---------------------------------------------------------------------------
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName               = $StockfishPath
$psi.UseShellExecute        = $false
$psi.RedirectStandardInput  = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError  = $true
$psi.CreateNoWindow         = $true

$proc = [System.Diagnostics.Process]::Start($psi)

# Initialize UCI and wait for "uciok"
$proc.StandardInput.WriteLine("uci")
$proc.StandardInput.Flush()
$initTimeout = 5000
$sw = [System.Diagnostics.Stopwatch]::StartNew()
while ($true) {
    if ($proc.StandardOutput.EndOfStream) {
        Write-Error "Stockfish process ended unexpectedly during init."
        exit 1
    }
    $initLine = $proc.StandardOutput.ReadLine()
    if ($initLine -eq "uciok") { break }
    if ($sw.ElapsedMilliseconds -gt $initTimeout) {
        Write-Error "Timeout waiting for Stockfish 'uciok'."
        exit 1
    }
}
$proc.StandardInput.WriteLine("isready")
$proc.StandardInput.Flush()
while ($true) {
    $readyLine = $proc.StandardOutput.ReadLine()
    if ($readyLine -eq "readyok") { break }
}

Write-Host "Stockfish ready. Starting annotation..."

# ---------------------------------------------------------------------------
# Helper: extract bare FEN from an EPD line (strips c0/c9 annotations)
# Returns empty string if the line should be skipped.
# ---------------------------------------------------------------------------
function Get-FenFromLine([string]$line) {
    # Strip trailing semicolon
    $stripped = $line.TrimEnd(';').Trim()

    # Remove c0/c9 annotations and bracketed results
    $stripped = $stripped -replace '\s+c[09]\s.*$', ''
    $stripped = $stripped -replace '\s+\[.*\].*$', ''
    $stripped = $stripped.Trim()

    # Split by whitespace and count fields
    $parts = $stripped -split '\s+'

    # Need at least 4 FEN fields (pieces color castling ep)
    if ($parts.Length -lt 4) { return '' }

    # Build full 6-field FEN (add halfmove/fullmove if absent)
    if ($parts.Length -ge 6) {
        return ($parts[0..5] -join ' ')
    } else {
        return ($parts[0..3] -join ' ') + ' 0 1'
    }
}

# ---------------------------------------------------------------------------
# Main annotation loop
# ---------------------------------------------------------------------------
$writer   = New-Object System.IO.StreamWriter($Output, $false, [System.Text.Encoding]::UTF8)
$count    = 0   # EPD lines processed
$written  = 0   # positions successfully annotated
$skipped  = 0   # positions skipped (mate, parse failure, in-check filter)

$lines = [System.IO.File]::ReadLines($InputEpd)

foreach ($rawLine in $lines) {
    $rawLine = $rawLine.Trim()
    if ([string]::IsNullOrEmpty($rawLine) -or $rawLine.StartsWith('#')) { continue }

    $fen = Get-FenFromLine $rawLine
    if ([string]::IsNullOrEmpty($fen)) {
        $skipped++
        continue
    }

    # Send position and request static eval
    $proc.StandardInput.WriteLine("position fen $fen")
    $proc.StandardInput.WriteLine("eval")
    $proc.StandardInput.Flush()

    # Read Stockfish output until "Final evaluation:" line
    # Stockfish prints many lines for eval; stop after finding the final eval or a sentinel
    $cpValue  = $null
    $maxLines = 300
    $linesRead = 0

    while ($linesRead -lt $maxLines) {
        $evalLine = $proc.StandardOutput.ReadLine()
        $linesRead++

        if ($null -eq $evalLine) { break }

        # "Final evaluation       +0.15 (white side) [...]" — SF18 format (no colon)
        # Also matches older format "Final evaluation: +0.47 (white side)"
        if ($evalLine -match 'Final evaluation[:\s]+([+-]?\d+\.?\d*)\s+\(white side\)') {
            $pawns   = [double]$Matches[1]
            $cpValue = [int][Math]::Round($pawns * 100.0)
            break
        }

        # "Final evaluation: none" (e.g. checkmate/stalemate position)
        if ($evalLine -match 'Final evaluation:\s+none') {
            break
        }
    }

    if ($null -ne $cpValue) {
        $writer.WriteLine("$fen $cpValue")
        $written++
    } else {
        $skipped++
    }

    $count++
    if ($count % 10000 -eq 0) {
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Progress: $count lines, $written written, $skipped skipped"
    }
}

$writer.Close()
$proc.StandardInput.WriteLine("quit")
try { $proc.WaitForExit(5000) | Out-Null } catch {}

Write-Host ""
Write-Host "Done: $count lines processed, $written positions annotated, $skipped skipped."
Write-Host "Output: $Output"
