<#
.SYNOPSIS
    Run the preregistered Phase 20 Stage 3 production Lazy SMP measurement.
.DESCRIPTION
    Builds this clean Phase 20 commit and drives its real UCI engine on native
    Windows. It refuses WSL, a changed Stage 2 environment, or a dirty branch.
#>
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repoRoot
try {
    $processPath = (Get-Process -Id $PID).Path
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT -or
        $env:WSL_INTEROP -or $env:WSL_DISTRO_NAME -or $env:WSLENV -or
        $repoRoot -notmatch '^[A-Za-z]:\\' -or $repoRoot.StartsWith('\\') -or
        $processPath -notmatch '(?i)\\(powershell|pwsh)(\.exe)?$') {
        throw 'Refusing to run: use PowerShell directly on native Windows in a local C:\... checkout, not WSL interop or a mounted path.'
    }

    $branchLines = @(& git rev-parse --abbrev-ref HEAD)
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the current branch.' }
    $branch = ($branchLines -join '').Trim()
    $headLines = @(& git rev-parse HEAD)
    if ($LASTEXITCODE -ne 0) { throw 'Could not read HEAD.' }
    $head = ($headLines -join '').Trim()
    $originHeadLines = @(& git rev-parse origin/phase/20-smp-qualification 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Could not read origin/phase/20-smp-qualification: $($originHeadLines -join ' ')" }
    $originHead = ($originHeadLines -join '').Trim()
    if ($head -ne $originHead) {
        throw "Checkout must be at the pushed branch tip before Stage 3; HEAD=$head origin=$originHead"
    }
    $statusLines = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'git status failed; cannot verify the working tree.' }
    $status = $statusLines -join "`n"
    if ($branch -ne 'phase/20-smp-qualification') { throw "Expected phase/20-smp-qualification, found $branch" }
    if ($status) { throw "Working tree must be clean before Stage 3:`n$status" }

    $stage2Base = 'b0f02bd79f8fbe9bff45037cdd307a9636978c91'
    $amendmentCommit = 'edd406a9243fcb53c341599d8c04e3fa7f34cab7'
    $resumeCommit = '00e20a48fe830cfc8f1652b281ba2306fe997969'
    foreach ($requiredCommit in @($stage2Base, $amendmentCommit, $resumeCommit)) {
        & git merge-base --is-ancestor $requiredCommit $head
        if ($LASTEXITCODE -ne 0) { throw "HEAD $head does not include required pre-Stage-3 commit $requiredCommit" }
    }

    $os = Get-CimInstance Win32_OperatingSystem
    $processors = @(Get-CimInstance Win32_Processor)
    $logicalCount = ($processors | Measure-Object -Property NumberOfLogicalProcessors -Sum).Sum
    $physicalCount = ($processors | Measure-Object -Property NumberOfCores -Sum).Sum
    $cpuName = ($processors | Select-Object -First 1).Name.Trim()
    if ($os.BuildNumber -ne '26200' -or $os.Caption -notmatch 'Windows 11 Pro' -or
        $cpuName -notmatch 'Ryzen 7 7700X' -or $physicalCount -ne 8 -or $logicalCount -ne 16) {
        throw "Stage 2 machine/Windows topology changed: $($os.Caption) build $($os.BuildNumber), $cpuName, $physicalCount cores/$logicalCount logical CPUs"
    }
    $powerPlan = (& powercfg /getactivescheme 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $powerPlan -notmatch '\(Balanced\)') {
        throw "Stage 3 requires the unchanged Balanced power plan; found: $powerPlan"
    }

    if ($env:JAVA_HOME) {
        $jdkBin = Join-Path $env:JAVA_HOME 'bin'
        $javaExe = Join-Path $jdkBin 'java.exe'
    } else {
        $javaExe = (Get-Command java -ErrorAction Stop).Source
        $jdkBin = Split-Path $javaExe -Parent
    }
    $javacExe = Join-Path $jdkBin 'javac.exe'
    if (-not (Test-Path $javaExe) -or -not (Test-Path $javacExe)) {
        throw "A full JDK is required; expected java.exe and javac.exe under $jdkBin."
    }
    $javaVersion = (& $javaExe -XshowSettings:properties -version 2>&1 | Out-String).Trim()
    $javacVersion = (& $javacExe -version 2>&1 | Out-String).Trim()
    if ($javaVersion -notmatch '(?i)(Azul|Zulu)' -or
        $javaVersion -notmatch '(?i)java\.runtime\.version\s*=\s*21\.0\.10\+7-LTS' -or
        $javacVersion -notmatch 'javac 21\.0\.10') {
        throw "Stage 3 requires the Stage 2 Azul Zulu JDK 21.0.10; found:`n$javaVersion"
    }

    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
    $outDir = Join-Path $repoRoot "tools\results\phase20-stage3\$timestamp"
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    $flags = @('-Xms512m', '-Xmx512m', '-XX:+UseG1GC', '--add-modules', 'jdk.incubator.vector')

    $environment = [ordered]@{
        branch = $branch; commit_sha = $head; jar_path = $null; jar_sha256 = $null
        post_247_develop_base = $stage2Base; amendment_commit = $amendmentCommit
        premeasurement_status_commit = $resumeCommit
        windows_caption = $os.Caption; windows_version = $os.Version; windows_build = $os.BuildNumber
        cpu_name = $cpuName; physical_cores = $physicalCount; logical_processors = $logicalCount
        java_exe = $javaExe; java_version_and_properties = $javaVersion; javac_version = $javacVersion
        jvm_flags = $flags; active_power_plan = $powerPlan
        affinity_policy = 'No affinity APIs or pinning; normal Windows scheduling'
        background_load_condition = '5-second per-process CPU sample immediately before measurement; unrelated workloads closed'
        background_load_sample_seconds = 5; background_load_top_processes = @()
        timing_os = 'Native Windows'; evaluation = 'Classical'; hash_mb = 16; pawn_hash_mb = 1
        depth = 13; positions = 31; worker_counts = @(1, 2, 4)
        own_book = $false; syzygy = $false; multipv = 1; ponder = $false; contempt_cp = 0
        instrumentation = 'SMP diagnostics on; helper node/time and post-drain hashfull counters opt-in'
        protocol = 'one discarded interleaved warm-up corpus; seven measured interleaved passes'
    }

    $mvnw = Join-Path $repoRoot 'mvnw.cmd'
    & $mvnw -pl engine-core,engine-uci -am clean package -DskipTests 2>&1 |
        Tee-Object -FilePath (Join-Path $outDir 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed; see build.log.' }

    $jar = Get-ChildItem (Join-Path $repoRoot 'engine-uci\target') -Filter 'engine-uci-*.jar' |
        Where-Object { $_.Name -notlike 'original-*' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'Could not find the shaded engine-uci JAR.' }
    $environment.jar_path = $jar.FullName
    $environment.jar_sha256 = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash

    $classes = Join-Path $outDir 'classes'
    New-Item -ItemType Directory -Path $classes | Out-Null
    & $javacExe -cp $jar.FullName -d $classes (Join-Path $repoRoot 'tools\Phase20Stage3Harness.java') 2>&1 |
        Tee-Object -FilePath (Join-Path $outDir 'harness-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Stage 3 harness compilation failed; see harness-build.log.' }

    $classpath = "$($jar.FullName);$classes"
    & $javaExe @flags -cp $classpath Phase20Stage3Harness --validate-only 2>&1 |
        Tee-Object -FilePath (Join-Path $outDir 'harness-selfcheck.log')
    if ($LASTEXITCODE -ne 0) { throw 'Stage 3 harness self-check failed; see harness-selfcheck.log.' }

    $before = @{}
    Get-Process -ErrorAction SilentlyContinue | ForEach-Object {
        if ($null -ne $_.CPU) { $before[$_.Id] = @{ name = $_.ProcessName; cpu = [double]$_.CPU } }
    }
    Start-Sleep -Seconds 5
    $background = @(Get-Process -ErrorAction SilentlyContinue | ForEach-Object {
        if ($before.ContainsKey($_.Id) -and $null -ne $_.CPU) {
            $delta = [Math]::Max(0, ([double]$_.CPU - $before[$_.Id].cpu))
            [pscustomobject]@{
                name = $_.ProcessName; pid = $_.Id
                machine_cpu_percent = [Math]::Round(100 * $delta / 5 / $logicalCount, 3)
            }
        }
    } | Sort-Object machine_cpu_percent -Descending | Select-Object -First 10)
    $environment.background_load_top_processes = $background
    $environment | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $outDir 'environment.json') -Encoding UTF8

    & $javaExe @flags -cp $classpath Phase20Stage3Harness --run $outDir $jar.FullName $javaExe 2>&1 |
        Tee-Object -FilePath (Join-Path $outDir 'harness-run.log')
    if ($LASTEXITCODE -ne 0) { throw "Stage 3 harness stopped; inspect partial CSVs and harness-run.log under $outDir." }

    Write-Host "Stage 3 raw evidence: $outDir"
    Write-Host "Run commit: $head"
    Write-Host "JAR SHA-256: $($environment.jar_sha256)"
} finally {
    Pop-Location
}
