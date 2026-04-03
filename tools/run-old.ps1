# Run the Vex-0.4.9 baseline JAR in UCI mode (stdin/stdout).
# Resolves the JAR relative to this script — no absolute paths.

$OldJar = Join-Path $PSScriptRoot 'engine-uci-0.4.9.jar'

if (-not (Test-Path $OldJar)) {
    Write-Error "Baseline JAR not found at: $OldJar"
    exit 1
}

Write-Host "Starting: $OldJar"
$Java = if ($env:JAVA) { $env:JAVA } else { 'java' }
& $Java -jar $OldJar
