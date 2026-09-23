#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ModsDirectory,
    [Parameter(Mandatory)][string]$EulaFile,
    [string]$JavaHome=$env:JAVA_HOME,
    [ValidateRange(60,3600)][int]$TimeoutSeconds=600,
    [switch]$Offline,
    [int]$Seed=132212,
    [string]$OutputRoot
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'system/ReportLibrary.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$JavaHome) { $JavaHome=Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source) }
$javaExe=Join-Path $JavaHome 'bin/java.exe'
$javac=& (Join-Path $JavaHome 'bin/javac.exe') -version 2>&1
if ("$javac" -notmatch 'javac 25(?:\.|\s|$)') { throw "JDK 25 required: $javac" }
if (!(Test-Path -LiteralPath $EulaFile -PathType Leaf) -or (Get-Content -LiteralPath $EulaFile -Raw) -notmatch '(?m)^\s*eula\s*=\s*true\s*$') { throw 'Provide an existing accepted eula.txt using -EulaFile' }
$dependencies=@()
foreach ($pattern in @('fabric-carpet-*.jar','carpet-org-addition-*.jar')) {
    $files=@(Get-ChildItem -LiteralPath $ModsDirectory -Filter $pattern -File)
    if ($files.Count -ne 1) { throw "Exactly one $pattern required, found $($files.Count)" }
    $dependencies+=$files[0].FullName
}
if (!$OutputRoot) { $OutputRoot=Join-Path $repo ('build/dedicated-e2e/'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)) }
$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if ((Test-Path -LiteralPath $OutputRoot) -and @(Get-ChildItem -LiteralPath $OutputRoot -Force).Count -gt 0) { throw 'OutputRoot must be new or empty' }
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$metadata=[ordered]@{schemaVersion=1;passed=$false;complete=$false;assertions=0;cases=@();seed=$Seed;started=[DateTimeOffset]::Now.ToString('o');java="$javac";processes=@();actors=@{};dependencies=@();cleanupErrors=@();evidenceErrors=@();timedOut=$false}
foreach ($dependency in $dependencies) { $metadata.dependencies+=@{file=(Split-Path $dependency -Leaf);sha256=(Get-FileHash -LiteralPath $dependency).Hash} }
$running=[Collections.Generic.List[object]]::new()
$timer=[Diagnostics.Stopwatch]::StartNew(); $lastProgress=0
$argumentEncoding=$null

function Add-RunnerFailure([string]$Name,[string]$Message) {
    $metadata.passed=$false
    $metadata.cases+=@{group='runner';name=$Name;status='FAIL';error=$Message;milliseconds=0}
    $metadata.assertions++
    $previous=Get-ReportField $metadata 'error' ''
    $metadata['error']=if ($previous) { "$previous`n$Name`: $Message" } else { "$Name`: $Message" }
}

function Read-LiveJson([string]$Path) {
    # Atomic writers need FileShare.Delete on Windows; ordinary Get-Content can block their rename.
    $stream=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    $reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8)
    try { return ($reader.ReadToEnd() | ConvertFrom-Json) } finally { $reader.Dispose() }
}
function Check-Time {
    if ($timer.Elapsed.TotalSeconds -gt $TimeoutSeconds) { $metadata.timedOut=$true;throw "Dedicated test exceeded $TimeoutSeconds seconds" }
    if ($timer.Elapsed.TotalSeconds -gt $script:lastProgress+15) {
        $script:lastProgress=[int]$timer.Elapsed.TotalSeconds
        Write-Host "[dedicated] Running ($($script:lastProgress)s), processes=$($running.Count)"
    }
}
function Quote-JavaArg([string]$Value) { return '"'+$Value.Replace('\','\\').Replace('"','\"')+'"' }
function Start-Role([string]$Role, $Spec, [int]$Port=0) {
    $dir=Join-Path $OutputRoot $Role
    $vm=@($Spec.jvmArgs | Where-Object { $_ -notmatch '^-Xm[sx]' -and $_ -notmatch '^-D(file.encoding|system\.)' })
    $identity=if ($Role -eq 'server') {'server'} else {$Role.Substring('client-'.Length)}
    $vm+=@('-Xms256M','-Xmx1500M','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Ddevauth.enabled=false',"-Dsystem.outputRoot=$dir","-Dsystem.role=$identity","-Dsystem.seed=$Seed",'-Dsystem.host=127.0.0.1',"-Dsystem.port=$Port")
    $argv=@($vm)+@('-classpath',$Spec.classpath,$Spec.mainClass)+@($Spec.args)
    if ($Role -ne 'server') { $argv+=@('--username',$identity) } else { $argv+='nogui' }
    $argFile=Join-Path $dir 'java.args'
    # The Windows Java launcher decodes @files with its native ANSI codepage,
    # before -Dfile.encoding is applied. Keep Unicode workspace paths intact.
    [IO.File]::WriteAllLines($argFile,@($argv | ForEach-Object { Quote-JavaArg ([string]$_) }),$argumentEncoding)
    $psi=[Diagnostics.ProcessStartInfo]::new()
    $psi.FileName=$javaExe; $psi.WorkingDirectory=$dir; $psi.UseShellExecute=$false; $psi.CreateNoWindow=$true
    $psi.RedirectStandardOutput=$true; $psi.RedirectStandardError=$true; $psi.RedirectStandardInput=$true
    $psi.Environment['JAVA_HOME']=$JavaHome
    $psi.ArgumentList.Add('@'+$argFile)
    $outFile=[IO.File]::Create((Join-Path $dir 'stdout.log')); $errFile=[IO.File]::Create((Join-Path $dir 'stderr.log'))
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$psi
    try { [void]$process.Start() } catch { $outFile.Dispose();$errFile.Dispose();$process.Dispose();throw }
    $process.StandardInput.Close()
    $item=[pscustomobject]@{role=$Role;dir=$dir;process=$process;stdout=$outFile;stderr=$errFile;outCopy=$process.StandardOutput.BaseStream.CopyToAsync($outFile);errCopy=$process.StandardError.BaseStream.CopyToAsync($errFile);started=[DateTimeOffset]::Now.ToString('o')}
    $running.Add($item)
    @{executable=$javaExe;arguments=@('@'+$argFile);expandedArguments=$argv;argumentFileCodePage=$argumentEncoding.CodePage;stdoutEncoding='UTF-8';stderrEncoding='UTF-8';workingDirectory=$dir;pid=$process.Id;started=$item.started} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $dir 'command.json') -Encoding utf8
    Write-Host "[dedicated] Started $Role PID=$($process.Id)"
    return $item
}
try {
    if (!('CommandGuiQA.NativeCodePage' -as [type])) {
        Add-Type -TypeDefinition 'using System.Runtime.InteropServices; namespace CommandGuiQA { public static class NativeCodePage { [DllImport("kernel32.dll")] public static extern uint GetACP(); } }'
    }
    [Text.Encoding]::RegisterProvider([Text.CodePagesEncodingProvider]::Instance)
    $codePage=[int][CommandGuiQA.NativeCodePage]::GetACP()
    $argumentEncoding=[Text.Encoding]::GetEncoding($codePage,[Text.EncoderExceptionFallback]::new(),[Text.DecoderExceptionFallback]::new())
    $metadata['javaArgumentEncoding']=@{codePage=$codePage;name=$argumentEncoding.WebName;source='Windows GetACP';stdout='UTF-8';stderr='UTF-8'}
    $gradleArgs=@('-classpath',(Join-Path $repo 'gradle/wrapper/gradle-wrapper.jar'),'org.gradle.wrapper.GradleWrapperMain',
        'prepareSystemTests','-I',(Join-Path $repo 'tests/system/init.gradle'),'--console=plain','--no-daemon','-x','bumpVersion',
        "-Dsystem.runRoot=$OutputRoot",'-Dorg.gradle.jvmargs=-Xmx2G -Dfile.encoding=COMPAT')
    if ($Offline) { $gradleArgs+='--offline' }
    Write-Host "[dedicated] Preparing test JAR and launch specs: $OutputRoot"
    $preparation=Invoke-SystemProcess -FileName $javaExe -Arguments $gradleArgs -WorkingDirectory $repo -LogDirectory (Join-Path $OutputRoot 'prepare') -TimeoutSeconds $TimeoutSeconds -Environment @{JAVA_HOME=$JavaHome}
    $metadata['preparation']=$preparation
    if ($preparation.timedOut) { $metadata.timedOut=$true }
    if ($preparation.exitCode -ne 0 -or $preparation.timedOut -or $preparation.error) { throw "Preparation failed; see prepare/stdout.log and stderr.log: $($preparation.error)" }
    $spec=Read-LiveJson (Join-Path $OutputRoot 'launch.json')
    $metadata['product']=@{file=$spec.productJar;sha256=(Get-FileHash -LiteralPath $spec.productJar).Hash}
    foreach ($role in @('server','client-ActorA','client-ActorB')) {
        $dir=Join-Path $OutputRoot $role
        New-Item -ItemType Directory -Path (Join-Path $dir 'mods') -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $dir '.system-test-isolated'),'isolated QA run')
        foreach ($jar in (@($spec.productJar,$spec.qaJar)+$dependencies)) { Copy-Item -LiteralPath $jar -Destination (Join-Path $dir 'mods') }
        @'
onboardAccessibility:false
startedCleanly:true
lang:zh_cn
renderDistance:2
simulationDistance:2
maxFps:30
pauseOnLostFocus:false
soundCategory_master:0.0
guiScale:2
'@ | Set-Content -LiteralPath (Join-Path $dir 'options.txt') -Encoding utf8
    }
    $serverDir=Join-Path $OutputRoot 'server'
    Copy-Item -LiteralPath $EulaFile -Destination (Join-Path $serverDir 'eula.txt')
    @'
server-ip=127.0.0.1
server-port=0
online-mode=false
enforce-secure-profile=false
generate-structures=false
level-type=minecraft:flat
generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}]}
view-distance=2
simulation-distance=2
spawn-protection=0
pause-when-empty-seconds=-1
max-tick-time=-1
gamemode=creative
difficulty=peaceful
op-permission-level=4
sync-chunk-writes=true
'@ | Set-Content -LiteralPath (Join-Path $serverDir 'server.properties') -Encoding utf8
    $server=Start-Role 'server' $spec.server
    $readyFile=Join-Path $serverDir 'ready.json'
    while (!(Test-Path -LiteralPath $readyFile)) {
        Check-Time
        if ($server.process.HasExited) { throw "Server exited before ready: $($server.process.ExitCode)" }
        Start-Sleep -Milliseconds 300
    }
    $ready=Read-LiveJson $readyFile
    if ($ready.ready -cne $true -or $ready.port -lt 1 -or $ready.port -gt 65535) { throw 'Invalid dedicated TCP readiness report' }
    $metadata['port']=$ready.port
    $actorA=Start-Role 'client-ActorA' $spec.client $ready.port
    $actorB=Start-Role 'client-ActorB' $spec.client $ready.port
    while (@($running | Where-Object { !$_.process.HasExited }).Count -gt 0) {
        Check-Time
        foreach ($actor in @($actorA,$actorB)) {
            if ($actor.process.HasExited -and $actor.process.ExitCode -ne 0) { throw "$($actor.role) exited with code $($actor.process.ExitCode)" }
            if ($actor.process.HasExited -and !(Test-Path -LiteralPath (Join-Path $actor.dir 'report.json'))) { throw "$($actor.role) exited without a report" }
            if ($actor.process.HasExited) {
                $actorReport=Read-LiveJson (Join-Path $actor.dir 'report.json')
                $metadata.actors[$actor.role]=$actorReport
                if ((Get-ReportField $actorReport 'passed') -isnot [bool] -or $actorReport.passed -cne $true) { throw "$($actor.role) exited with a failed or invalid report; stopping peers immediately" }
                if ((Get-ReportField $actorReport 'complete') -isnot [bool] -or $actorReport.complete -cne $true) { throw "$($actor.role) exited with an incomplete report; stopping peers immediately" }
            }
        }
        if ($server.process.HasExited -and $server.process.ExitCode -ne 0) { throw "Server exited with code $($server.process.ExitCode)" }
        Start-Sleep -Milliseconds 300
    }
    foreach ($item in $running) {
        if ($item.process.ExitCode -ne 0) { throw "$($item.role) exited with code $($item.process.ExitCode)" }
        $report=Read-LiveJson (Join-Path $item.dir 'report.json')
        $metadata.actors[$item.role]=$report
        $minimum=if ($item.role -eq 'server') {14} elseif ($item.role -eq 'client-ActorA') {60} else {25}
        $suite=Read-CaseSuite $report "$($item.role)/" $minimum 10
        if ($report.passed -cne $true) { throw "$($item.role) reported failure" }
    }
    $metadata.complete=$true; $metadata.passed=$true
} catch {
    Add-RunnerFailure 'dedicated supervision and evidence validation' $_.Exception.ToString()
    Write-Host "[dedicated] FAIL: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    try {
        try {
            $stopFile=Join-Path $OutputRoot 'server/stop.request'
            if (Test-Path -LiteralPath (Split-Path $stopFile)) { [IO.File]::WriteAllText($stopFile,'runner cleanup') }
        } catch { $metadata.cleanupErrors+=$_.Exception.Message;Add-RunnerFailure 'write server stop request' $_.Exception.Message }
        foreach ($item in $running) {
            $processEvidence=[ordered]@{role=$item.role;pid=$null;exitCode=$null;started=$item.started;finished=$null;forcedKill=$false;cleanupErrors=@()}
            try {
                $processEvidence.pid=$item.process.Id
                if (!$item.process.HasExited -and !$item.process.WaitForExit(2000)) {
                    $processEvidence.forcedKill=$true
                    $item.process.Kill($true)
                    if (!$item.process.WaitForExit(5000)) { throw 'Process did not exit within 5 seconds after tree kill' }
                }
                if ($item.process.HasExited) { $processEvidence.exitCode=$item.process.ExitCode }
            } catch {
                $processEvidence.cleanupErrors+=$_.Exception.Message
                $metadata.cleanupErrors+="$($item.role): $($_.Exception.Message)"
                Add-RunnerFailure "cleanup $($item.role)" $_.Exception.Message
            }
            try {
                if (![Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($item.outCopy,$item.errCopy),3000)) { throw 'Log stream drain exceeded 3 seconds' }
            } catch {
                $processEvidence.cleanupErrors+=$_.Exception.Message
                $metadata.cleanupErrors+="$($item.role) logs: $($_.Exception.Message)"
                Add-RunnerFailure "log drain $($item.role)" $_.Exception.Message
            }
            finally {
                foreach ($resource in @($item.stdout,$item.stderr,$item.process)) {
                    try { $resource.Dispose() }
                    catch { $processEvidence.cleanupErrors+=$_.Exception.Message;$metadata.cleanupErrors+="$($item.role) dispose: $($_.Exception.Message)";Add-RunnerFailure "dispose $($item.role)" $_.Exception.Message }
                }
                $processEvidence.finished=[DateTimeOffset]::Now.ToString('o')
                $metadata.processes+=@($processEvidence)
            }
        }
        # Harvest after peers have exited so a failing run retains every available
        # role's passed/failed cases, snapshots, packet counters and original report.
        foreach ($role in @('server','client-ActorA','client-ActorB')) {
            $roleReport=Join-Path $OutputRoot "$role/report.json"
            $raw=$null
            try {
                if (Test-Path -LiteralPath $roleReport -PathType Leaf) { $raw=Read-LiveJson $roleReport;$metadata.actors[$role]=$raw }
                elseif ($metadata.actors.ContainsKey($role)) { $raw=$metadata.actors[$role] }
                else { throw 'Role did not produce a report; see its stdout/stderr and events.jsonl' }
                $evidence=Read-PartialSuiteEvidence $raw "$role/"
                $metadata.cases+=@($evidence.cases);$metadata.assertions+=$evidence.assertions
            } catch {
                $metadata.evidenceErrors+="$role`: $($_.Exception.Message)"
                Add-RunnerFailure "collect $role evidence" $_.Exception.Message
            }
        }
    } catch {
        $metadata.cleanupErrors+=$_.Exception.ToString()
        Add-RunnerFailure 'unexpected cleanup error' $_.Exception.ToString()
    } finally {
        $metadata['finished']=[DateTimeOffset]::Now.ToString('o')
        $metadata['durationSeconds']=$timer.Elapsed.TotalSeconds
        $metadata | ConvertTo-Json -Depth 70 | Set-Content -LiteralPath (Join-Path $OutputRoot 'report.json') -Encoding utf8
        Write-Host "[dedicated] PASS=$($metadata.passed); report=$(Join-Path $OutputRoot 'report.json')"
    }
}
if (!$metadata.passed) { exit 1 }
exit 0
