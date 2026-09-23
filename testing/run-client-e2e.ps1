#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$ModsDirectory,
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(60,3600)][int]$TimeoutSeconds = 600,
    [switch]$Offline,
    [switch]$WithoutCarpet
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$JavaHome) { $JavaHome = Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source) }
$javaExe = Join-Path $JavaHome 'bin/java.exe'
$javac = & (Join-Path $JavaHome 'bin/javac.exe') -version 2>&1
if ("$javac" -notmatch 'javac 25(?:\.|\s|$)') { throw "需要 JDK 25，当前：$javac" }
$dependencies = @()
if (!$WithoutCarpet) {
    if (!$ModsDirectory) { throw '需要 ModsDirectory，或指定 WithoutCarpet 验证无可选依赖。' }
    foreach ($pattern in @('fabric-carpet-*.jar', 'carpet-org-addition-*.jar')) {
        $files = @(Get-ChildItem -LiteralPath $ModsDirectory -Filter $pattern -File)
        if ($files.Count -ne 1) { throw "ModsDirectory 需包含唯一 $pattern，发现 $($files.Count) 个" }
        $dependencies += $files[0].FullName
    }
}
$mode = if ($WithoutCarpet) { 'without-carpet' } else { 'with-carpet' }
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $mode + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$outputRoot = Join-Path $repo "build/client-e2e/$runId"
$runDir = Join-Path $outputRoot 'run'
New-Item -ItemType Directory -Path (Join-Path $runDir 'mods') -Force | Out-Null
foreach ($dependency in $dependencies) { Copy-Item -LiteralPath $dependency -Destination (Join-Path $runDir 'mods') }
@'
onboardAccessibility:false
startedCleanly:true
lang:zh_cn
renderDistance:2
simulationDistance:2
maxFps:60
pauseOnLostFocus:false
soundCategory_master:0.0
guiScale:2
'@ | Set-Content -LiteralPath (Join-Path $runDir 'options.txt') -Encoding utf8
$metadata = [ordered]@{ mode=$mode; passed=$false; java="$javac"; dependencies=@(); started=(Get-Date).ToString('o') }
foreach ($dependency in $dependencies) { $metadata.dependencies += @{ file=(Split-Path $dependency -Leaf); sha256=(Get-FileHash -LiteralPath $dependency).Hash } }
$process = $null
$stdout = $null
try {
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
        ':runClient', '-I', (Join-Path $repo 'tests/e2e/init.gradle'), '--console=plain', '--no-daemon', '-x', 'bumpVersion',
        "-De2e.runDir=$runDir", '-Dorg.gradle.jvmargs=-Xmx2G -Dfile.encoding=COMPAT')
    if ($Offline) { $arguments += '--offline' }
    foreach ($argument in $arguments) { $psi.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $psi
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $timer = [Diagnostics.Stopwatch]::StartNew(); $lastProgress = 0
    Write-Host "[e2e] $mode，真实客户端与集成服务端，输出 $outputRoot"
    while (!$process.WaitForExit(500)) {
        if ($timer.Elapsed.TotalSeconds -gt $TimeoutSeconds) { throw "端到端运行超过 $TimeoutSeconds 秒" }
        if ($timer.Elapsed.TotalSeconds -ge $lastProgress + 15) {
            $lastProgress = [int]$timer.Elapsed.TotalSeconds
            Write-Host "[e2e] 运行中 (${lastProgress}s)"
        }
    }
    $metadata['processExitCode'] = $process.ExitCode
    if ($process.ExitCode -ne 0) { throw "客户端进程退出码 $($process.ExitCode)" }
    $report = Get-Content -LiteralPath (Join-Path $runDir 'e2e-report.json') -Raw | ConvertFrom-Json
    $metadata['suite'] = $report
    if ($report.passed -cne $true -or $report.complete -cne $true -or @($report.cases).Count -ne 22 -or
        $report.assertions -lt 40 -or @($report.cases | Where-Object status -CNE 'PASS').Count -ne 0) {
        throw '客户端用例失败或未完整执行，请查看 client.log 与 run/e2e-report.json'
    }
    if ($report.carpet -eq [bool]$WithoutCarpet) { throw '可选依赖测试环境与请求模式不一致' }
    $metadata.passed = $true
} catch {
    $metadata['error'] = $_.Exception.Message
    Write-Host "[e2e] FAIL：$($metadata.error)" -ForegroundColor Red
} finally {
    if ($null -ne $stdout) {
        if (!$process.HasExited) { $process.Kill($true); $process.WaitForExit() }
        [IO.File]::WriteAllText((Join-Path $outputRoot 'client.log'), $stdout.GetAwaiter().GetResult() + "`n" + $stderr.GetAwaiter().GetResult(), [Text.Encoding]::UTF8)
    }
    if ($null -ne $process) { $process.Dispose() }
    $metadata['finished'] = (Get-Date).ToString('o')
    $metadata | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $outputRoot 'report.json') -Encoding utf8
    Write-Host "[e2e] PASS=$($metadata.passed)，报告：$(Join-Path $outputRoot 'report.json')"
}
if (!$metadata.passed) { exit 1 }
exit 0
