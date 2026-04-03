# Run the latest engine-uci JAR in UCI mode (stdin/stdout).
# Resolves the JAR relative to this script — no absolute paths.

$TargetDir = Join-Path $PSScriptRoot '..' 'engine-uci' 'target'
$Jar = Get-ChildItem -Path $TargetDir -Filter 'engine-uci-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch '^original-' } |
       Sort-Object LastWriteTime -Descending |
       Select-Object -First 1

if (-not $Jar) {
    Write-Error "No engine-uci JAR found in $TargetDir.`nRun: .\mvnw.cmd -pl engine-uci -am package -DskipTests"
    exit 1
}

Write-Host "Starting: $($Jar.FullName)"
$Java = if ($env:JAVA) { $env:JAVA } else { 'java' }
& $Java -jar $Jar.FullName
