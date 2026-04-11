<#
.SYNOPSIS
    Launch the Vex chess engine JAR with explicit JVM heap and GC settings.

.DESCRIPTION
    Sets -Xmx<Heap>, -XX:+UseG1GC and -XX:MaxGCPauseMillis=5 before launching
    the engine JAR.  Use this script as the engine command in Cutechess/Arena to
    avoid the JVM default heap cap (~25% RAM) causing GC pauses that degrade NPS
    under multi-threading.

    The full java command is printed to stderr before execution so the user can
    verify the launch configuration.

.PARAMETER Heap
    JVM max heap size passed to -Xmx (default: "512m").
    Examples: "512m", "1g", "2g".
    For Hash=256 + 2 threads, use at least "1g".

.PARAMETER Jar
    Path to the engine JAR file (default: "engine-uci-latest.jar" in the same
    directory as this script).

.PARAMETER Args
    Additional arguments forwarded verbatim to the engine JAR (e.g.
    "--param-overrides path/to/overrides.txt").  Passed as a single string;
    use quoting appropriate for your shell.

.EXAMPLE
    # Minimal — uses all defaults
    .\launch_vex.ps1

.EXAMPLE
    # Larger heap for correspondence / analysis
    .\launch_vex.ps1 -Heap 1g -Jar tools\engine-uci-eval-converged.jar

.EXAMPLE
    # Cutechess engine command line (set as "cmd=" in engines.json):
    #   cmd="powershell -File C:\path\to\launch_vex.ps1" arg="-Jar" arg="engine.jar"

.NOTES
    -XX:MaxGCPauseMillis=5 is aggressive but appropriate for a time-controlled
    engine: G1GC targets sub-5ms pause, which is negligible compared to typical
    50-500ms move time budgets.  Actual pauses may exceed the target under heap
    pressure, but are far shorter than the JVM default (up to 200ms with Parallel GC).
#>

param(
    [string] $Heap = "512m",
    [string] $Jar  = "",
    [string] $Args = ""
)

# Resolve default JAR: look for engine-uci-*.jar next to this script
if ([string]::IsNullOrEmpty($Jar)) {
    $matches = Get-ChildItem -Path $PSScriptRoot -Filter "engine-uci-*.jar" -ErrorAction SilentlyContinue |
               Sort-Object Name -Descending | Select-Object -First 1
    if ($matches) {
        $Jar = $matches.FullName
    } else {
        Write-Error "No engine JAR found in $PSScriptRoot and -Jar was not specified."
        exit 1
    }
}

# Resolve java executable
$java = if ($env:JAVA) {
    $env:JAVA
} elseif ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    'java'
}

# Build JVM argument list
$jvmArgs = @(
    "-Xmx$Heap",
    "-XX:+UseG1GC",
    "-XX:MaxGCPauseMillis=5"
)

# Build full invocation
$engineArgs = @("-jar", $Jar)
if (-not [string]::IsNullOrEmpty($Args)) {
    # Split the extra args string on whitespace, respecting simple quoting is left to the caller
    $engineArgs += $Args -split '\s+'
}

$fullCommand = "$java $($jvmArgs -join ' ') $($engineArgs -join ' ')"
[Console]::Error.WriteLine("info string launch_vex.ps1: $fullCommand")

# Execute — this replaces the current process context for UCI (stdin/stdout pass-through)
& $java @jvmArgs @engineArgs
exit $LASTEXITCODE
