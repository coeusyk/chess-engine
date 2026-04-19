#Requires -Version 7
<#
.SYNOPSIS
    Phase 13 (#140): Sample from quiet-labeled.epd as the Texel tuning base corpus.

.DESCRIPTION
    Samples up to -MaxPositions positions from the Stockfish-annotated quiet-labeled.epd
    base corpus and writes a working EPD file for use by the Texel tuner via
    --corpus-format epd.

    Optionally appends targeted seed positions from -AugmentFens, Stockfish-annotated
    at the specified depth for coverage augmentation (Issue #135).

    The self-play PGN extraction pipeline has been removed (#140). quiet-labeled.epd
    is the primary corpus source.

.PARAMETER BaseEpd
    Required. Path to quiet-labeled.epd (or a compatible annotated EPD with c0/c9 or
    bracket result annotations).

.PARAMETER AugmentFens
    Optional. Path to an EPD/FEN file of specific seed positions (one FEN per line,
    # comments ignored). These are Stockfish-annotated and appended as c9 "<result>"
    entries to the output EPD. Requires -StockfishPath.

.PARAMETER OutputEpd
    Output EPD file path. Default: data/texel_corpus.epd

.PARAMETER MaxPositions
    Maximum number of positions to sample from the base EPD. Default: 100000.

.PARAMETER StockfishPath
    Path to the Stockfish binary. Required when -AugmentFens is specified.

.PARAMETER Threads
    Stockfish annotation parallelism for -AugmentFens. Default: 8.

.PARAMETER Depth
    Stockfish analysis depth for -AugmentFens annotation. Default: 12.

.PARAMETER K
    Sigmoid constant K = 400/k_calibrated for WDL classification of augmented FENs.
    Centipawn threshold: cp > K*0.4 → win, cp < -K*0.4 → loss, else draw.
    Default: 570 (matches TunerEvaluator.java with k_calibrated ≈ 0.701544).

.EXAMPLE
    .\tools\generate_texel_corpus.ps1 -BaseEpd data\quiet-labeled.epd

.EXAMPLE
    .\tools\generate_texel_corpus.ps1 -BaseEpd data\quiet-labeled.epd `
        -AugmentFens tools\seeds\king_attack_seeds.epd `
        -StockfishPath "C:\Tools\stockfish.exe"
#>
param(
    [Parameter(Mandatory)][string]$BaseEpd,
    [string]$AugmentFens   = "",
    [string]$OutputEpd     = (Join-Path $PSScriptRoot ".." "data" "texel_corpus.epd"),
    [int]   $MaxPositions  = 100000,
    [string]$StockfishPath = "",
    [int]   $Threads       = 8,
    [int]   $Depth         = 12,
    [double]$K             = 570.0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Validate inputs
# ---------------------------------------------------------------------------
$BaseEpdResolved = Resolve-Path $BaseEpd -ErrorAction SilentlyContinue
if (-not $BaseEpdResolved) {
    Write-Error "Base EPD not found: $BaseEpd"
    exit 1
}

if ($AugmentFens -ne "" -and -not (Test-Path $AugmentFens)) {
    Write-Error "-AugmentFens file not found: $AugmentFens"
    exit 1
}

if ($AugmentFens -ne "" -and $StockfishPath -eq "") {
    Write-Error "-AugmentFens requires -StockfishPath."
    exit 1
}

if ($AugmentFens -ne "" -and -not (Test-Path $StockfishPath)) {
    Write-Error "Stockfish binary not found: $StockfishPath"
    exit 1
}

# Ensure output directory exists
$outDir = Split-Path $OutputEpd -Parent
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

Write-Host ""
Write-Host "Generate Texel Corpus (EPD)" -ForegroundColor Cyan
Write-Host "===========================" -ForegroundColor Cyan
Write-Host "Base EPD     : $($BaseEpdResolved.Path)"
Write-Host "Max positions: $MaxPositions"
Write-Host "Output EPD   : $OutputEpd"
if ($AugmentFens -ne "") {
    Write-Host "Augment FENs : $AugmentFens  (Stockfish depth=$Depth)"
}
Write-Host ""

# ---------------------------------------------------------------------------
# Step 1: Sample from the base EPD
# ---------------------------------------------------------------------------
Write-Host "Step 1: Sampling up to $MaxPositions positions from base EPD..." -ForegroundColor Cyan
$sampled   = [System.Collections.Generic.List[string]]::new()
$knownFens = [System.Collections.Generic.HashSet[string]]::new()
$lineCount = 0

Get-Content $BaseEpdResolved.Path | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    $lineCount++
    if ($sampled.Count -ge $MaxPositions) { return }
    # Deduplicate by 4-field FEN prefix (position, side, castling, ep)
    $fenKey = ($line -split '\s+')[0..3] -join ' '
    if ($knownFens.Add($fenKey)) { $sampled.Add($line) }
}

Write-Host "  Read $lineCount lines, sampled $($sampled.Count) unique positions." -ForegroundColor Green
if ($sampled.Count -eq 0) {
    Write-Error "No positions sampled from base EPD — check file format and path."
    exit 1
}

# ---------------------------------------------------------------------------
# Step 2 (optional): Stockfish-annotate augmented FEN seeds
# ---------------------------------------------------------------------------
$augmented = [System.Collections.Generic.List[string]]::new()
if ($AugmentFens -ne "") {
    Write-Host ""
    Write-Host "Step 2: Annotating augmented FENs with Stockfish (depth=$Depth)..." -ForegroundColor Cyan
    $seedFens = Get-Content $AugmentFens |
        Where-Object { $_.Trim() -ne "" -and -not $_.Trim().StartsWith("#") } |
        ForEach-Object { $_.Trim() }
    Write-Host "  Loaded $($seedFens.Count) seed FENs."

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName               = $StockfishPath
    $psi.UseShellExecute        = $false
    $psi.RedirectStandardInput  = $true
    $psi.RedirectStandardOutput = $true
    $psi.CreateNoWindow         = $true
    $seedProc = [System.Diagnostics.Process]::new()
    $seedProc.StartInfo = $psi
    [void]$seedProc.Start()

    # Handshake
    $seedProc.StandardInput.WriteLine("uci")
    $seedProc.StandardInput.WriteLine("isready")
    $seedProc.StandardInput.Flush()
    $timo = [DateTime]::UtcNow.AddSeconds(10)
    while ([DateTime]::UtcNow -lt $timo) {
        $l = $seedProc.StandardOutput.ReadLine()
        if ($null -eq $l -or $l -eq 'readyok') { break }
    }

    $seedAdded = 0
    foreach ($fen in $seedFens) {
        $fenParts = $fen -split '\s+'
        $fullFen  = if ($fenParts.Count -ge 6) { $fen } else { "$fen 0 1" }
        try {
            $seedProc.StandardInput.WriteLine("position fen $fullFen")
            $seedProc.StandardInput.WriteLine("go depth $Depth")
            $seedProc.StandardInput.Flush()
            $evalCp = $null
            $timo2  = [DateTime]::UtcNow.AddSeconds(60)
            while ([DateTime]::UtcNow -lt $timo2) {
                $line = $seedProc.StandardOutput.ReadLine()
                if ($null -eq $line) { break }
                if ($line -match 'info.*\bscore cp (-?\d+)') { $evalCp = [int]$Matches[1] }
                if ($line -match 'info.*\bscore mate (-?\d+)') {
                    $evalCp = if ([int]$Matches[1] -gt 0) { 30000 } else { -30000 }
                }
                if ($line -match '^bestmove') { break }
            }
            if ($null -ne $evalCp) {
                # Classify: cp > K*0.4 → win for side to move, cp < -K*0.4 → loss, else draw
                $threshold = $K * 0.4
                $side = if ($fenParts.Count -gt 1) { $fenParts[1] } else { 'w' }
                if ($evalCp -gt $threshold) {
                    $result = if ($side -eq 'b') { "0-1" } else { "1-0" }
                } elseif ($evalCp -lt -$threshold) {
                    $result = if ($side -eq 'b') { "1-0" } else { "0-1" }
                } else {
                    $result = "1/2-1/2"
                }
                $augmented.Add("$($fenParts[0..3] -join ' ') c9 `"$result`";")
                $seedAdded++
            }
        } catch {
            Write-Warning "  Annotation error for FEN: $fen — $_"
        }
    }
    try { $seedProc.StandardInput.WriteLine("quit"); $seedProc.StandardInput.Flush() } catch {}
    $seedProc.WaitForExit(3000) | Out-Null
    if (-not $seedProc.HasExited) { try { $seedProc.Kill() } catch {} }
    Write-Host "  Annotated $seedAdded augmented positions." -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# Step 3: Write output EPD
# ---------------------------------------------------------------------------
$totalOut = $sampled.Count + $augmented.Count
Write-Host ""
Write-Host "Step 3: Writing $totalOut positions to $OutputEpd..." -ForegroundColor Cyan

$sb = [System.Text.StringBuilder]::new()
foreach ($line in $sampled)   { [void]$sb.AppendLine($line) }
foreach ($line in $augmented) { [void]$sb.AppendLine($line) }
[System.IO.File]::WriteAllText($OutputEpd, $sb.ToString(), [System.Text.Encoding]::UTF8)

Write-Host ""
Write-Host "Corpus written: $totalOut positions to $OutputEpd" -ForegroundColor Green
Write-Host "  Base sample : $($sampled.Count)"
Write-Host "  Augmented   : $($augmented.Count)"
Write-Host "Next step    : java -jar engine-tuner.jar $OutputEpd --corpus-format epd"
