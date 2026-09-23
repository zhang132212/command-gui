#Requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputRoot,[string]$JavaHome=$env:JAVA_HOME,[ValidateRange(30,3600)][int]$TimeoutSeconds=600,[switch]$Offline)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ReportLibrary.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if ((Test-Path -LiteralPath $OutputRoot) -and @(Get-ChildItem -LiteralPath $OutputRoot -Force).Count -gt 0) { throw 'OutputRoot must be new or empty' }
$runDir=Join-Path $OutputRoot 'run';New-Item -ItemType Directory -Path (Join-Path $runDir 'mods') -Force | Out-Null
$report=[ordered]@{schemaVersion=1;passed=$false;complete=$false;assertions=0;cases=@();started=[DateTimeOffset]::Now.ToString('o')}
try {
    if (!$JavaHome) { $JavaHome=Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source) }
    $javaExe=Join-Path $JavaHome 'bin/java.exe'
    $version=& (Join-Path $JavaHome 'bin/javac.exe') -version 2>&1
    if ("$version" -notmatch 'javac 25(?:\.|\s|$)') { throw "JDK 25 required: $version" }
    $report['java']=[string]$version
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
    $groovyPath=$runDir.Replace('\','/').Replace("'","\'")
    $override=@"
// Per-run isolation for the existing editor-layout QA mod.
gradle.projectsEvaluated {
    def p = gradle.rootProject
    p.tasks.named('editorQaJar') { destinationDirectory = p.file('$groovyPath/mods') }
    p.loom.runs.client { runDir '$groovyPath' }
}
"@
    $overridePath=Join-Path $OutputRoot 'isolated-layout.gradle'
    Set-Content -LiteralPath $overridePath -Value $override -Encoding utf8
    $arguments=@('-classpath',(Join-Path $repo 'gradle/wrapper/gradle-wrapper.jar'),'org.gradle.wrapper.GradleWrapperMain',':runClient','-I',(Join-Path $repo 'tests/editor-layout/init.gradle'),'-I',$overridePath,'--console=plain','--no-daemon','-x','bumpVersion','-Dorg.gradle.jvmargs=-Xmx2G -Dfile.encoding=COMPAT')
    if ($Offline) { $arguments+='--offline' }
    $process=Invoke-SystemProcess -FileName $javaExe -Arguments $arguments -WorkingDirectory $repo -LogDirectory (Join-Path $OutputRoot 'process') -TimeoutSeconds $TimeoutSeconds -Environment @{JAVA_HOME=$JavaHome}
    $report['process']=$process
    if ($process.timedOut -or $process.error -or $process.exitCode -ne 0) { throw "Layout process failed: $($process.error); exit=$($process.exitCode)" }
    $log=(Get-Content -LiteralPath $process.stdout -Raw -Encoding utf8)+(Get-Content -LiteralPath $process.stderr -Raw -Encoding utf8)
    if ($log -match 'EDITOR_LAYOUT_QA_FAILED|CLIENT_REGRESSIONS_FAILED|GUI_THEME_QA_FAILED') { throw 'Layout QA printed a failure marker' }
    if ([regex]::Matches($log,'(?m)^.*EDITOR_LAYOUT_QA_COMPLETE\s*$').Count -ne 1) { throw 'Exactly one EDITOR_LAYOUT_QA_COMPLETE marker is required' }
    if ([regex]::Matches($log,'CARPET_FILTER_QA_COMPLETE combinations=4 search=ok category=ok pending=ok resize=ok').Count -ne 1) { throw 'Carpet filter completion marker missing or repeated' }
    # Each of three sizes checks AddCommandScreen four times, MachineEditor once, Settings once.
    $bounds=[regex]::Matches($log,'EDITOR_LAYOUT_BOUNDS_OK (\S+) (\d+x\d+)')
    if ($bounds.Count -ne 18) { throw "Expected 18 bounds markers, found $($bounds.Count)" }
    foreach ($size in @('720x450','480x300','320x240')) {
        foreach ($screen in @(@('AddCommandScreen',4),@('MachineEditorScreen',1),@('SettingsScreen',1))) {
            $observed=@($bounds | Where-Object { $_.Groups[1].Value -ceq $screen[0] -and $_.Groups[2].Value -ceq $size }).Count
            if ($observed -ne $screen[1]) { throw "Bounds marker mismatch: $($screen[0]) at $size, expected=$($screen[1]), actual=$observed" }
        }
        $report.cases += @{group='layout';name="widgets-and-draft-$size";status='PASS';steps=@('command/fake/custom editors bounds','resize preserves unsaved draft','machine editor bounds','settings state follows external changes','fake-player selection toggle');milliseconds=0;error=''}
    }
    $expectedShots=@()
    for ($size=0;$size -lt 3;$size++) { foreach ($name in @('command','fake-command','custom-fake-command','machine','settings-off','settings-on-focused','settings-off-focused','fake-controls-smooth')) { $expectedShots += "editor-$size-$name.png" } }
    $expectedShots += 'editor-3-tooltip-opaque.png'
    foreach ($name in $expectedShots) {
        $path=Join-Path $runDir "screenshots/$name"
        if (!(Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -lt 100) { throw "Missing or empty screenshot: $name" }
    }
    $report.cases += @{group='layout';name='carpet-filter-search-category-pending-resize';status='PASS';milliseconds=0;error=''}
    $report.cases += @{group='layout';name='screenshots-complete';status='PASS';milliseconds=0;error='';steps=$expectedShots}
    # Count only independently checked completion/bounds/screenshots evidence, not internal assertions that emit no counter.
    $report.assertions=2+$bounds.Count+$expectedShots.Count
    $report.complete=$true;$report.passed=$true
} catch { $report['error']=$_.Exception.ToString();Write-Host "[layout] FAIL: $($report.error)" }
finally {
    $report['finished']=[DateTimeOffset]::Now.ToString('o')
    $report['expectedCases']=5
    $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $OutputRoot 'report.json') -Encoding utf8
}
if (!$report.passed) { exit 1 };exit 0
