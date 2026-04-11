<#
.SYNOPSIS
    Monitor memory usage of SPRT engine processes (2T vs 1T) in real time.
    Samples every 30 seconds and logs to a CSV file.

.PARAMETER OutputFile
    Path to output CSV file. Default: tools/results/smp_memory_monitor.csv

.PARAMETER SampleIntervalSeconds
    Time between samples in seconds. Default: 30
#>
param(
    [string]$OutputFile = (Join-Path $PSScriptRoot "results\smp_memory_monitor.csv"),
    [int]$SampleIntervalSeconds = 30
)

$ResultsDir = Split-Path $OutputFile
if (-not (Test-Path $ResultsDir)) { New-Item -ItemType Directory -Path $ResultsDir | Out-Null }

Write-Host "Memory monitor started. Output: $OutputFile"
Write-Host "Sampling every $SampleIntervalSeconds seconds. Press Ctrl+C to stop."
Write-Host ""

# Write CSV header
@"
Timestamp,Process2T_CurrentMB,Process2T_PeakMB,Process1T_CurrentMB,Process1T_PeakMB,Difference_MB,Total_MB
"@ | Out-File -FilePath $OutputFile -Encoding UTF8

$iteration = 0
while ($true) {
    $iteration++
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    
    $procs = @(Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.StartTime -gt [datetime]"2026-04-10 20:48" } | Sort-Object Id)
    
    if ($procs.Count -ge 2) {
        $p2t_cur = [math]::Round($procs[0].WorkingSet / 1MB, 2)
        $p2t_peak = [math]::Round($procs[0].PeakWorkingSet / 1MB, 2)
        $p1t_cur = [math]::Round($procs[1].WorkingSet / 1MB, 2)
        $p1t_peak = [math]::Round($procs[1].PeakWorkingSet / 1MB, 2)
        $diff = [math]::Round($p2t_cur - $p1t_cur, 2)
        $total = [math]::Round($p2t_cur + $p1t_cur, 2)
        
        # Log to CSV
        "$timestamp,$p2t_cur,$p2t_peak,$p1t_cur,$p1t_peak,$diff,$total" | Add-Content -Path $OutputFile
        
        # Display
        Write-Host "[$iteration] $timestamp | 2T: $p2t_cur MB (peak $p2t_peak) | 1T: $p1t_cur MB (peak $p1t_peak) | Diff: +$diff MB | Total: $total MB"
    } else {
        Write-Host "[$iteration] $timestamp | No SPRT processes detected"
    }
    
    Start-Sleep -Seconds $SampleIntervalSeconds
}
