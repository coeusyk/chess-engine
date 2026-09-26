<#
.SYNOPSIS
    Build and run the frozen Phase 20 Stage 6 qualification on the qualified host.
.DESCRIPTION
    -SelfCheckOnly builds the tools and runs deterministic synthetic checks without
    starting any measured engine searches. The default path requires the qualified
    native Windows 7700X host and runs the preregistered Stage 6 once.
#>
param([switch]$SelfCheckOnly)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repoRoot
try {
    $branch = (& git rev-parse --abbrev-ref HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the current branch.' }
    $head = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not read HEAD.' }

    if (-not $SelfCheckOnly) {
        $processPath = (Get-Process -Id $PID).Path
        if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT -or
            $env:WSL_INTEROP -or $env:WSL_DISTRO_NAME -or $env:WSLENV -or
            $repoRoot -notmatch '^[A-Za-z]:\\' -or $repoRoot.StartsWith('\\') -or
            $processPath -notmatch '(?i)\\(powershell|pwsh)(\.exe)?$') {
            throw 'Refusing timed Stage 6 work outside native Windows PowerShell in a local drive checkout.'
        }
        if ($branch -ne 'phase/20-smp-qualification') { throw "Expected phase/20-smp-qualification, found $branch" }
        $originHead = (& git rev-parse origin/phase/20-smp-qualification).Trim()
        if ($LASTEXITCODE -ne 0 -or $head -ne $originHead) {
            throw "Checkout must equal the pushed branch tip before Stage 6; HEAD=$head origin=$originHead"
        }
        $status = @(& git status --porcelain)
        if ($LASTEXITCODE -ne 0 -or $status.Count -ne 0) { throw "Working tree must be clean before Stage 6:`n$($status -join "`n")" }

        $expected = '1bf47c4544decb72a1fa25e7d6ad7b8e199dc115'
        $baselineCore = (& git rev-parse "$expected`:engine-core/src/main").Trim()
        $currentCore = (& git rev-parse 'HEAD:engine-core/src/main').Trim()
        $baselineUci = (& git rev-parse "$expected`:engine-uci/src/main").Trim()
        $currentUci = (& git rev-parse 'HEAD:engine-uci/src/main').Trim()
        if ($baselineCore -ne $currentCore -or $baselineUci -ne $currentUci) {
            throw 'Production search/UCI source differs from the frozen Stage 6 baseline.'
        }

        $os = Get-CimInstance Win32_OperatingSystem
        $processors = @(Get-CimInstance Win32_Processor)
        $logicalCount = ($processors | Measure-Object -Property NumberOfLogicalProcessors -Sum).Sum
        $physicalCount = ($processors | Measure-Object -Property NumberOfCores -Sum).Sum
        $cpuName = ($processors | Select-Object -First 1).Name.Trim()
        if ($os.BuildNumber -ne '26200' -or $os.Caption -notmatch 'Windows 11 Pro' -or
            $cpuName -notmatch 'Ryzen 7 7700X' -or $physicalCount -ne 8 -or $logicalCount -ne 16) {
            throw "Stage 6 requires qualified Windows 11 build 26200 / Ryzen 7 7700X / 8-core 16-thread host; found $($os.Caption) $($os.BuildNumber), $cpuName, $physicalCount/$logicalCount"
        }
        $powerPlan = (& powercfg /getactivescheme 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or $powerPlan -notmatch '\(Balanced\)') {
            throw "Stage 6 requires the qualified Balanced power plan; found: $powerPlan"
        }
    }

    if ($env:JAVA_HOME) {
        $jdkBin = Join-Path $env:JAVA_HOME 'bin'
        $javaExe = Join-Path $jdkBin 'java.exe'
    } else {
        $javaExe = (Get-Command java -ErrorAction Stop).Source
        $jdkBin = Split-Path $javaExe -Parent
    }
    $javacExe = Join-Path $jdkBin 'javac.exe'
    if (-not (Test-Path $javaExe) -or -not (Test-Path $javacExe)) { throw "A full JDK is required under $jdkBin" }
    $javaVersion = (& $javaExe -XshowSettings:properties -version 2>&1 | Out-String).Trim()
    $javacVersion = (& $javacExe -version 2>&1 | Out-String).Trim()
    if ($SelfCheckOnly) {
        if ($javacVersion -notmatch 'javac (2[1-9]|[3-9][0-9])\.') { throw "Self-check compilation requires JDK 21+; found $javacVersion" }
    } elseif ($javaVersion -notmatch '(?i)(Azul|Zulu)' -or
              $javaVersion -notmatch '(?i)java\.runtime\.version\s*=\s*21\.0\.10\+7-LTS' -or
              $javacVersion -notmatch 'javac 21\.0\.10') {
        throw "Stage 6 requires qualified Azul Zulu JDK 21.0.10; found:`n$javaVersion`n$javacVersion"
    }

    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss-fff')
    $outDir = Join-Path $repoRoot "tools\results\phase20-stage6\$timestamp"
    if (Test-Path $outDir) { throw "Run artifact directory already exists: $outDir" }
    New-Item -ItemType Directory -Path $outDir | Out-Null
    $flags = @('-Xms512m', '-Xmx512m', '-XX:+UseG1GC', '--add-modules', 'jdk.incubator.vector')
    $mvnw = Join-Path $repoRoot 'mvnw.cmd'
    & $mvnw -pl engine-core,engine-uci -am clean package -DskipTests 2>&1 |
        Tee-Object -FilePath (Join-Path $outDir 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Maven packaging failed; see build.log.' }

    $jar = Get-ChildItem (Join-Path $repoRoot 'engine-uci\target') -Filter 'engine-uci-*.jar' |
        Where-Object { $_.Name -notlike 'original-*' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'Could not find the shaded engine-uci JAR.' }
    $classes = Join-Path $outDir 'classes'
    New-Item -ItemType Directory -Path $classes | Out-Null
    $sources = @('Phase20UciEvents.java', 'Phase20Stage3Harness.java', 'Phase20Stage6Harness.java') |
        ForEach-Object { Join-Path $repoRoot "tools\$_" }
    & $javacExe -cp $jar.FullName -d $classes $sources 2>&1 |
        Tee-Object -FilePath (Join-Path $outDir 'harness-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Stage 6 harness compilation failed; see harness-build.log.' }
    $classpath = "$($jar.FullName);$classes"
    & $javaExe @flags -cp $classpath Phase20Stage3Harness --validate-only 2>&1 |
        Tee-Object -FilePath (Join-Path $outDir 'stage3-accounting-selfcheck.log')
    if ($LASTEXITCODE -ne 0) { throw 'Shared Stage 3 event-accounting self-check failed.' }
    & $javaExe @flags -cp $classpath Phase20Stage6Harness --validate-only 2>&1 |
        Tee-Object -FilePath (Join-Path $outDir 'stage6-selfcheck.log')
    if ($LASTEXITCODE -ne 0) { throw 'Stage 6 deterministic self-check failed.' }
    if ($SelfCheckOnly) {
        Write-Host "Stage 6 local self-check artifacts: $outDir"
        return
    }

    $before = @{}
    Get-Process -ErrorAction SilentlyContinue | ForEach-Object {
        if ($null -ne $_.CPU) { $before[$_.Id] = @{ name = $_.ProcessName; cpu = [double]$_.CPU } }
    }
    Start-Sleep -Seconds 5
    $background = @(Get-Process -ErrorAction SilentlyContinue | ForEach-Object {
        if ($before.ContainsKey($_.Id) -and $null -ne $_.CPU) {
            $delta = [Math]::Max(0, ([double]$_.CPU - $before[$_.Id].cpu))
            [pscustomobject]@{ name = $_.ProcessName; pid = $_.Id; machine_cpu_percent = [Math]::Round(100 * $delta / 5 / $logicalCount, 3) }
        }
    } | Sort-Object machine_cpu_percent -Descending | Select-Object -First 10)

    $fileHashes = [ordered]@{}
    foreach ($source in $sources) { $fileHashes[(Split-Path $source -Leaf)] = (Get-FileHash $source -Algorithm SHA256).Hash }
    $identity = [ordered]@{
        baseline_commit = '1bf47c4544decb72a1fa25e7d6ad7b8e199dc115'; tooling_commit = $head
        production_core_tree = $currentCore; production_uci_tree = $currentUci
        branch = $branch; windows_caption = $os.Caption; windows_version = $os.Version; windows_build = $os.BuildNumber
        cpu_name = $cpuName; physical_cores = $physicalCount; logical_processors = $logicalCount
        java_exe = $javaExe; java_version = $javaVersion; javac_version = $javacVersion
        jvm_flags = $flags; active_power_plan = $powerPlan; affinity = 'none; normal Windows scheduling'
        jar_path = $jar.FullName; jar_sha256 = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash
        harness_sha256 = $fileHashes; hash_mb = 16; pawn_hash_mb = 1; evaluator = 'Classical'
        own_book = $false; syzygy_online = $false; multipv = 1; contempt = 0; move_overhead_ms = 30
        threads = @(1, 2, 4); modes = @('movetime: go movetime 1950', 'clock: go wtime 60000 btime 60000 winc 600 binc 600')
        warmup_searches = 186; measured_searches = 1302; references_maximum = 31
        background_load_sample_seconds = 5; background_load_top_processes = $background
        protocol = 'Phase 20 Stage 6 frozen preregistration'
    }
    $identityPath = Join-Path $outDir 'identity.json'
    [IO.File]::WriteAllText($identityPath, ($identity | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    & $javaExe @flags -cp $classpath Phase20Stage6Harness --run $outDir $jar.FullName $javaExe $identityPath 2>&1 |
        Tee-Object -FilePath (Join-Path $outDir 'harness-run.log')
    if ($LASTEXITCODE -ne 0) { throw "Stage 6 stopped; preserve artifacts and inspect failure.txt under $outDir." }
    Write-Host "Stage 6 evidence: $outDir"
    Write-Host "Run commit: $head"
    Write-Host "JAR SHA-256: $($identity.jar_sha256)"
} finally {
    Pop-Location
}
