#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ModsDirectory,
    [Parameter(Mandatory)][string]$EulaFile,
    [string]$JavaHome = $env:JAVA_HOME,
    [int]$TimeoutSeconds = 600,
    [switch]$Offline,
    [switch]$SkipRestart
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($TimeoutSeconds -lt 30) { throw 'TimeoutSeconds 必须至少为 30 秒。' }
if (!(Test-Path -LiteralPath $EulaFile) -or (Get-Content -LiteralPath $EulaFile -Raw) -notmatch '(?m)^\s*eula\s*=\s*true\s*$') {
    throw 'EulaFile 必须指向你已接受 Minecraft EULA 的 eula.txt。脚本不会代为接受协议。'
}
if (!$JavaHome) {
    $javaCommand = Get-Command javac -ErrorAction SilentlyContinue
    if ($javaCommand) { $JavaHome = Split-Path (Split-Path $javaCommand.Source) }
}
if (!$JavaHome) { throw '未找到 JDK，请用 -JavaHome 指定 JDK 25。' }
$javaExe = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $javaExe)) { throw '请用 -JavaHome 指定 JDK 25。' }
$javacVersion = & (Join-Path $JavaHome 'bin/javac.exe') -version 2>&1
if ("$javacVersion" -notmatch 'javac 25(?:\.|\s|$)') { throw "要求 JDK 25，当前：$javacVersion" }
$dependencies = @()
foreach ($pattern in @('fabric-carpet-*.jar', 'carpet-org-addition-*.jar')) {
    $matches = @(Get-ChildItem -LiteralPath $ModsDirectory -Filter $pattern -File)
    if ($matches.Count -ne 1) { throw "ModsDirectory 必须包含且只包含一个 $pattern（发现 $($matches.Count) 个）。" }
    $dependencies += $matches[0].FullName
}
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$outputRoot = Join-Path $repo "build/backend-tests/$runId"
$runDir = Join-Path $outputRoot 'run'
New-Item -ItemType Directory -Path (Join-Path $runDir 'mods') -Force | Out-Null
Set-Content -LiteralPath (Join-Path $runDir '.backend-test-isolated') -Value $runId -Encoding utf8
Copy-Item -LiteralPath $EulaFile -Destination (Join-Path $runDir 'eula.txt')
foreach ($dependency in $dependencies) { Copy-Item -LiteralPath $dependency -Destination (Join-Path $runDir 'mods') }
@'
server-ip=127.0.0.1
server-port=0
online-mode=false
enforce-secure-profile=false
level-name=backend-test-world
level-type=minecraft\:flat
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
gamemode=creative
difficulty=peaceful
max-tick-time=-1
pause-when-empty-seconds=-1
enable-rcon=false
enable-query=false
sync-chunk-writes=true
'@ | Set-Content -LiteralPath (Join-Path $runDir 'server.properties') -Encoding utf8
$metadata = [ordered]@{ runId=$runId; java="$javacVersion"; dependencies=@(); phases=@(); started=(Get-Date).ToString('o') }
foreach ($dependency in $dependencies) { $metadata.dependencies += @{file=(Split-Path $dependency -Leaf);sha256=(Get-FileHash -LiteralPath $dependency).Hash} }
$failed = $false
try {
    $phases = @('suite')
    if (!$SkipRestart) { $phases += 'restart' }
    foreach ($phase in $phases) {
        Write-Host "[backend] $phase：启动隔离专用服务端，报告目录 $outputRoot"
        $psi = [Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = $javaExe
        $psi.WorkingDirectory = $repo
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.StandardOutputEncoding = [Text.Encoding]::UTF8
        $psi.StandardErrorEncoding = [Text.Encoding]::UTF8
        $psi.Environment['JAVA_HOME'] = $JavaHome
        $arguments = @('-classpath', (Join-Path $repo 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain',
            ':runServer', '-I', (Join-Path $PSScriptRoot 'backend/init.gradle'), '--console=plain', '--no-daemon', '-x', 'bumpVersion',
            "-Dbackend.runDir=$runDir", "-Dbackend.phase=$phase", '-Dorg.gradle.jvmargs=-Xmx2G -Dfile.encoding=COMPAT')
        if ($Offline) { $arguments += '--offline' }
        foreach ($argument in $arguments) { $psi.ArgumentList.Add($argument) }
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $psi
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $lastProgress = 0
        try {
            while (!$process.WaitForExit(500)) {
                if ($timer.Elapsed.TotalSeconds -gt $TimeoutSeconds) { throw "$phase 超时（$TimeoutSeconds 秒）" }
                if ($timer.Elapsed.TotalSeconds -ge $lastProgress + 15) {
                    $lastProgress = [int]$timer.Elapsed.TotalSeconds
                    Write-Host "[backend] $phase 运行中（${lastProgress}s）"
                }
            }
        } finally {
            if (!$process.HasExited) { $process.Kill($true); $process.WaitForExit() }
            $log = $stdout.GetAwaiter().GetResult() + "`n" + $stderr.GetAwaiter().GetResult()
            [IO.File]::WriteAllText((Join-Path $outputRoot "$phase.log"), $log, [Text.Encoding]::UTF8)
        }
        $reportPath = Join-Path $runDir "backend-$phase.json"
        if (!(Test-Path -LiteralPath $reportPath)) { throw "$phase 未生成结果（启动失败或崩溃），请查看 $phase.log" }
        $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
        $metadata.phases += $report
        Write-Host "[backend] $phase：通过 $($report.passed)，失败 $($report.failed)，断言 $($report.assertions)"
        if ($process.ExitCode -ne 0 -or !$report.complete -or $report.failed -gt 0) { $failed = $true }
    }
    if ($SkipRestart) { $metadata['notRun'] = @('跨进程重启持久化（SkipRestart）') }
} catch {
    $failed = $true
    $metadata['error'] = $_.Exception.Message
    Write-Host "[backend] 失败：$($_.Exception.Message)" -ForegroundColor Red
} finally {
    $metadata['finished'] = (Get-Date).ToString('o')
    $metadata['passed'] = !$failed -and !$SkipRestart
    $metadata | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $outputRoot 'report.json') -Encoding utf8
    $xmlSettings = [Xml.XmlWriterSettings]::new()
    $xmlSettings.Indent = $true
    $xmlSettings.Encoding = [Text.UTF8Encoding]::new($false)
    $xml = [Xml.XmlWriter]::Create((Join-Path $outputRoot 'junit.xml'), $xmlSettings)
    try {
        $xml.WriteStartDocument()
        $xml.WriteStartElement('testsuites')
        foreach ($phaseReport in $metadata.phases) {
            $xml.WriteStartElement('testsuite')
            $xml.WriteAttributeString('name', [string]$phaseReport.phase)
            $xml.WriteAttributeString('tests', [string]$phaseReport.cases.Count)
            $xml.WriteAttributeString('failures', [string]$phaseReport.failed)
            foreach ($case in $phaseReport.cases) {
                $xml.WriteStartElement('testcase')
                $xml.WriteAttributeString('classname', [string]$case.group)
                $xml.WriteAttributeString('name', [string]$case.name)
                if ($case.status -ne 'PASS') { $xml.WriteElementString('failure', [string]$case.error) }
                $xml.WriteEndElement()
            }
            $xml.WriteEndElement()
        }
        if ($metadata.Contains('error') -or $SkipRestart) {
            $xml.WriteStartElement('testsuite')
            $xml.WriteAttributeString('name', 'runner')
            $xml.WriteAttributeString('tests', '1')
            $xml.WriteStartElement('testcase')
            $xml.WriteAttributeString('name', 'complete-run')
            if ($metadata.Contains('error')) { $xml.WriteElementString('error', [string]$metadata.error) }
            else { $xml.WriteElementString('skipped', 'Restart phase not run') }
            $xml.WriteEndElement()
            $xml.WriteEndElement()
        }
        $xml.WriteEndElement()
        $xml.WriteEndDocument()
    } finally { $xml.Dispose() }
    $lines = @('# Command-GUI 后端测试', '', "结果：$(if($failed){'FAIL'}elseif($SkipRestart){'PARTIAL'}else{'PASS'})", '', '|阶段|用例|状态|说明|', '|---|---|---|---|')
    foreach ($phaseReport in $metadata.phases) {
        foreach ($case in $phaseReport.cases) { $lines += "|$($phaseReport.phase)|$($case.group)/$($case.name)|$($case.status)|$(([string]$case.error).Replace('|','/').Replace("`n",' '))|" }
    }
    if ($metadata.Contains('error')) { $lines += "`n运行错误：$($metadata.error)" }
    if ($SkipRestart) { $lines += "`n未运行：跨进程重启持久化。此次结果不算全量通过。" }
    $lines | Set-Content -LiteralPath (Join-Path $outputRoot 'report.md') -Encoding utf8
    Write-Host "[backend] 完整报告：$(Join-Path $outputRoot 'report.md')"
}
if ($failed) { exit 1 }
if ($SkipRestart) { exit 2 }
exit 0
