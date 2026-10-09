# Synthetic checks only: no executable fixtures are launched and no games are played.
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'phase21-stage6-common.ps1')
$script:checks=0
function Assert-Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Validation failed: $Message" }
    $script:checks++
}
function Assert-Rejected([scriptblock]$Action, [string]$Message) {
    $rejected=$false
    try { & $Action | Out-Null } catch { $rejected=$true }
    Assert-Check $rejected $Message
}
function New-FixtureGame([string]$White, [string]$Result, [string]$Fen='fixture opening') {
    $black=if($White -eq 'NEW'){'OLD'}else{'NEW'}
    return "[Event `"fixture`"]`n[White `"$White`"]`n[Black `"$black`"]`n[Result `"$Result`"]`n[FEN `"$Fen`"]`n[TimeControl `"5+0.05`"]`n`n1. e4 e5 $Result`n`n"
}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('phase21-stage6-validation-'+[guid]::NewGuid().ToString('N'))
$oldJava=$env:JAVA; $oldCute=$env:CUTECHESS
New-Item -ItemType Directory -Path (Join-Path $temp 'jdk\bin') | Out-Null
try {
    # Inert files make accidental launch fail; PrepareOnly must never invoke them.
    foreach($name in @('cutechess.exe','candidate.jar','control.jar','noob.epd','jdk\bin\java.exe')) {
        Set-Content -LiteralPath (Join-Path $temp $name) -Value 'inert fixture'
    }
    $env:JAVA=Join-Path $temp 'jdk'; $env:CUTECHESS=Join-Path $temp 'cutechess.exe'
    $config=Get-Stage6Config
    Assert-Check ($config.ControlCommit -ceq 'd3a56ffadf0d9151a2b19fba4902a734a99bff96' -and $config.CandidateCommit -ceq 'e90d3d4f90b244c46e8bbf75c33222bba7d58003') 'source identities'
    Assert-Check ($config.OpeningSha256 -ceq '2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347') 'corpus identity'
    $parameters=Get-Stage6Parameters $temp (Join-Path $temp 'noob.epd')
    Assert-Stage6Parameters $parameters
    foreach($key in @('Elo0','Elo1','Alpha','Beta','BonferroniM','MinGames','MaxGames','TC','Concurrency','EngineThreads')) {
        $bad=$parameters.Clone(); $bad[$key]=if($key -eq 'TC'){'60+0.6'}else{999}
        Assert-Rejected { Assert-Stage6Parameters $bad } "reject incorrect $key"
    }
    $bad=$parameters.Clone(); $bad.NewOptions=@('Hash=32')
    Assert-Rejected { Assert-Stage6Parameters $bad } 'reject asymmetric options'
    foreach($key in @('New','Old','OpeningsFile','LogPath','PgnPath')) {
        $bad=$parameters.Clone(); $bad.Remove($key)
        Assert-Rejected { Assert-Stage6Parameters $bad } "reject missing $key"
    }
    $bad=$parameters.Clone(); $bad.LogPath=$bad.PgnPath
    Assert-Rejected { Assert-Stage6Parameters $bad } 'reject colliding evidence paths'
    $facts=@{ Platform='Win32NT';ComputerName='RENEGADE';CpuName='AMD Ryzen 7 7700X 8-Core Processor'
        PowerPlanRaw='381b4222-f694-41f0-9685-ff5bb260df2e'; AffinityMask=65535;FullAffinityMask=65535
        JavaVersion='21.0.10';JavaRuntime='21.0.10+7-LTS';JavaVendor='Azul Systems, Inc.'
        MavenVersion='3.9.16';JavaHome='C:\jdk';MavenJavaHome='C:\jdk';CutechessVersion='1.5.1';OsBuild='26300' }
    Assert-Stage6HostFacts $facts
    $facts.OsBuild='99999'; Assert-Stage6HostFacts $facts
    foreach($key in @('Platform','ComputerName','CpuName','PowerPlanRaw','JavaVersion','JavaRuntime','JavaVendor','MavenVersion','MavenJavaHome','CutechessVersion')) {
        $bad=$facts.Clone(); $bad[$key]='wrong'
        Assert-Rejected { Assert-Stage6HostFacts $bad } "reject incorrect host/tool $key"
    }
    $bad=$facts.Clone(); $bad.AffinityMask=1
    Assert-Rejected { Assert-Stage6HostFacts $bad } 'reject restricted affinity'
    $manifestPath=Join-Path $temp 'prepared.json'
    & (Join-Path $PSScriptRoot 'sprt.ps1') @parameters -PrepareOnly -ArgumentManifestPath $manifestPath
    $manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    Assert-Stage6Arguments $manifest $parameters (Join-Path $env:JAVA 'bin\java.exe') $env:CUTECHESS
    Assert-Check (-not (Test-Path -LiteralPath $parameters.LogPath) -and -not (Test-Path -LiteralPath $parameters.PgnPath)) 'preparation creates no match output'
    $manifest.Arguments[0]='-wrong'
    Assert-Rejected { Assert-Stage6Arguments $manifest $parameters (Join-Path $env:JAVA 'bin\java.exe') $env:CUTECHESS } 'reject changed manifest'
    Save-Stage6Json $manifest (Join-Path $temp 'wrong.json')
    Assert-Rejected { & (Join-Path $PSScriptRoot 'sprt.ps1') @parameters -PrepareOnly -ArgumentManifestPath $manifestPath -ExpectedArgumentManifestPath (Join-Path $temp 'wrong.json') } 'generic runner refuses changed arguments without launch'
    $pgn=(New-FixtureGame 'NEW' '1-0')+(New-FixtureGame 'OLD' '0-1')
    $log="Finished game 1 (NEW vs OLD): 1-0 {White wins by adjudication}`nFinished game 2 (OLD vs NEW): 0-1 {Black wins by adjudication}`nScore of NEW vs OLD: 2 - 0 - 0  [1.000] 2`nSPRT: llr 3.1 (105.4%), lbound -2.94, ubound 2.94 - H1 was accepted`nFinished match`n"
    $r=Read-Stage6Results $log $pgn 0
    Assert-Check ($r.Verdict -eq 'H1_ACCEPTED' -and $r.Wins -eq 2 -and $r.CompletedOpeningPairs -eq 1 -and $r.UnpairedScoredGames -eq 0) 'H1 and paired colors'
    Assert-Check ($r.CandidateByColor.White.Wins -eq 1 -and $r.CandidateByColor.Black.Wins -eq 1) 'candidate color orientation'
    $h0=$log.Replace('llr 3.1','llr -3.1').Replace('H1 was accepted','H0 was accepted')
    Assert-Check ((Read-Stage6Results $h0 $pgn 0).Verdict -eq 'H0_ACCEPTED') 'H0 classification'
    $cancelled=$log.Replace('Finished match',"Finished game 3 (NEW vs OLD): * {No result}`nFinished match")
    $cancelPgn=$pgn+(New-FixtureGame 'NEW' '*')
    $r=Read-Stage6Results $cancelled $cancelPgn 0
    Assert-Check ($r.Verdict -eq 'H1_ACCEPTED' -and $r.Cancelled -eq 1 -and $r.Scored -eq 2) 'boundary cancellations excluded from score and retained'
    foreach($fault in @('White loses on time','Black wins on time','connection stalls','engine crashed','Warning: NEW disconnected','illegal move','Unknown option Hash','Engine NEW does not support option Hash')) {
        Assert-Check ((Read-Stage6Results ($log+$fault) $pgn 0).Verdict -eq 'SETUP_PROTOCOL_FAULT') "fault overrides H1: $fault"
    }
    Assert-Check ((Read-Stage6Results $log $pgn 1).Verdict -eq 'SETUP_PROTOCOL_FAULT') 'nonzero exit overrides H1'
    Assert-Check ((Read-Stage6Results $log $pgn 0 $true).Verdict -eq 'INTERRUPTED') 'interruption blocks promotion'
    foreach($badLog in @($log.Replace('2 - 0 - 0','1 - 1 - 0'),$log.Replace('game 2','game 1'),$log.Replace('ubound 2.94','ubound 4.0'),$log.Replace('llr 3.1','llr 0.5'),$log.Replace(' - H1 was accepted',''))) {
        Assert-Check ((Read-Stage6Results $badLog $pgn 0).Verdict -eq 'SETUP_PROTOCOL_FAULT') 'reject inconsistent score/IDs/bounds/decision'
    }
    foreach($badPgn in @($pgn.Replace('5+0.05','60+0.6'),$pgn.Replace('[White "NEW"]','[White "OTHER"]'),($pgn+'[Event "partial"]'))) {
        Assert-Check ((Read-Stage6Results $log $badPgn 0).Verdict -eq 'SETUP_PROTOCOL_FAULT') 'reject incorrect or partial PGN'
    }
    $drawPgn=(New-FixtureGame 'NEW' '1/2-1/2')+(New-FixtureGame 'OLD' '1-0')
    $drawLog=$h0.Replace('1 (NEW vs OLD): 1-0','1 (NEW vs OLD): 1/2-1/2').Replace('2 (OLD vs NEW): 0-1','2 (OLD vs NEW): 1-0').Replace('2 - 0 - 0','0 - 1 - 1')
    $r=Read-Stage6Results $drawLog $drawPgn 0
    Assert-Check ($r.Draws -eq 1 -and $r.Losses -eq 1 -and $r.Verdict -eq 'H0_ACCEPTED') 'draw and loss orientation'
    Assert-Check ((Get-Stage6Verdict '' 20000 $true $false 0 $false) -eq 'INCONCLUSIVE') 'cap without boundary'
    Assert-Check ((Get-Stage6Verdict '' 19999 $true $false 0 $false) -eq 'INCOMPLETE') 'early stop without boundary'
    Assert-Check ((Get-Stage6Verdict 'H1' 20000 $true $false 0 $false) -eq 'H1_ACCEPTED') 'boundary at cap'
    Assert-Check ((Get-Stage6Verdict 'H1' 2 $false $false 0 $false) -eq 'INCOMPLETE') 'unfinished match blocks promotion'
    Assert-Check ((Read-Stage6Results '' '' 1).Verdict -eq 'SETUP_PROTOCOL_FAULT') 'setup failure without evidence'
    # Load function definitions only; never evaluate the native wrapper's main body.
    $parseErrors=$null; $tokens=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'phase21-stage6.ps1'),[ref]$tokens,[ref]$parseErrors)
    Assert-Check ($parseErrors.Count -eq 0) 'wrapper syntax'
    foreach($name in @('Invoke-Stage6Checked','Stop-Stage6OwnedProcess','Clear-Stage6BuildWorktrees','Assert-Stage6Jar')) {
        $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$false)
        . ([scriptblock]::Create($definition.Extent.Text))
    }
    Assert-Rejected { Assert-Stage6Jar (Join-Path $temp 'missing.jar') } 'absent executable JAR rejected'
    Assert-Rejected { Assert-Stage6Jar $parameters.New } 'non-JAR binary rejected'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zipPath=Join-Path $temp 'fixture.jar'
    $zip=[IO.Compression.ZipFile]::Open($zipPath,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach($name in @('META-INF/MANIFEST.MF','coeusyk/game/chess/uci/UciApplication.class','coeusyk/game/chess/core/search/Searcher.class')) {
            $writer=New-Object IO.StreamWriter($zip.CreateEntry($name).Open())
            try { $writer.WriteLine('Main-Class: coeusyk.game.chess.uci.UciApplication') } finally { $writer.Dispose() }
        }
    } finally { $zip.Dispose() }
    Assert-Stage6Jar $zipPath; $script:checks++
    $failedLog=Join-Path $temp 'failed-command.log'
    Assert-Rejected { Invoke-Stage6Checked 'cmd.exe' @('/d','/c','echo retained-stdout & echo retained-stderr 1>&2 & exit /b 7') $temp $failedLog } 'native nonzero exit is rejected'
    $retained=Get-Content -LiteralPath $failedLog -Raw
    Assert-Check ($retained -match 'retained-stdout' -and $retained -match 'retained-stderr') 'failed command retains stdout and stderr'
    Assert-Check ($ErrorActionPreference -eq 'Stop') 'native helper restores error preference'
    $script:stopped=@()
    function taskkill.exe { $script:stopped=@($args); 'synthetic stop' }
    $fake=[pscustomobject]@{ Id=424242; HasExited=$false }
    $fake | Add-Member -MemberType ScriptMethod -Name WaitForExit -Value {param($ms) $this.HasExited=$true; return $true}
    Stop-Stage6OwnedProcess $fake $temp
    Assert-Check (($script:stopped -join ' ') -ceq '/PID 424242 /T /F') 'stop targets only the owned process tree'
    $script:stopped=@(); Stop-Stage6OwnedProcess $fake $temp
    Assert-Check ($script:stopped.Count -eq 0) 'already-exited child is not stopped'
    $script:removed=@()
    function Get-Stage6Git($Repository,$Arguments) { $script:removed=@($Arguments); throw 'synthetic dirty worktree' }
    $root=Join-Path $temp 'owned-build-root'; New-Item -ItemType Directory -Path $root | Out-Null
    Set-Content -LiteralPath (Join-Path $root 'preserved.txt') -Value 'partial evidence'
    Clear-Stage6BuildWorktrees 'fixture' @('owned-worktree') $root $true
    Assert-Check (($script:removed -join ' ') -ceq 'worktree remove owned-worktree') 'cleanup never forces worktree removal'
    Assert-Check (Test-Path -LiteralPath (Join-Path $root 'preserved.txt')) 'dirty worktree and partial artifacts preserved'
    Remove-Item -LiteralPath (Join-Path $root 'preserved.txt')
    Clear-Stage6BuildWorktrees 'fixture' @() $root $false
    Assert-Check (Test-Path -LiteralPath $root) 'unowned empty root preserved'
    Clear-Stage6BuildWorktrees 'fixture' @() $root $true
    Assert-Check (-not (Test-Path -LiteralPath $root)) 'owned empty root removed'
    Assert-Check (Test-Path -LiteralPath $failedLog) 'failed command evidence survives cleanup'
    Write-Host "Stage 6 synthetic validation PASS: $script:checks checks. No builds, engines, Cute Chess or games launched."
}
finally {
    $env:JAVA=$oldJava; $env:CUTECHESS=$oldCute
    # Only this validation invocation's unique temporary fixture directory is removed.
    Remove-Item -LiteralPath $temp -Recurse -Force
}
