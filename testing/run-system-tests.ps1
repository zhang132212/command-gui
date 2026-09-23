#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$ModsDirectory,
    [string]$EulaFile,
    [string]$JavaHome=$env:JAVA_HOME,
    [ValidateRange(60,3600)][int]$TimeoutSeconds=600,
    [switch]$Offline,
    [int]$Seed=132212,
    [string]$OutputRoot,
    [ValidateSet('backend','integrated-carpet','integrated-vanilla','dedicated','layout','runner-selftest')]
    [string[]]$Phases=@('backend','integrated-carpet','integrated-vanilla','dedicated','layout','runner-selftest')
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'system/ReportLibrary.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runId=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
if (!$OutputRoot) { $OutputRoot=Join-Path $repo "build/system-tests/$runId" }
$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if ((Test-Path -LiteralPath $OutputRoot) -and @(Get-ChildItem -LiteralPath $OutputRoot -Force).Count -gt 0) { throw 'OutputRoot must be new or empty; stale reports must never be reused' }
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
if ($ModsDirectory) { $ModsDirectory=[IO.Path]::GetFullPath($ModsDirectory) }
if ($EulaFile) { $EulaFile=[IO.Path]::GetFullPath($EulaFile) }
if ($JavaHome) { $JavaHome=[IO.Path]::GetFullPath($JavaHome) }
$definitions=@(
    @{id='backend';kind='backend';script='run-backend-tests.ps1';factor=2},
    @{id='integrated-carpet';kind='integrated';script='run-client-e2e.ps1';factor=1},
    @{id='integrated-vanilla';kind='integrated';script='run-client-e2e.ps1';factor=1},
    @{id='dedicated';kind='dedicated';script='run-dedicated-e2e.ps1';factor=1},
    @{id='layout';kind='layout';script='system/run-layout-tests.ps1';factor=1},
    @{id='runner-selftest';kind='selftest';script='system/test-reporting.ps1';factor=1}
)
$commit=(& git -C $repo rev-parse HEAD 2>$null | Out-String).Trim()
$dirty=(& git -C $repo status --porcelain 2>$null | Out-String).Trim()
$sourceFiles=@()
foreach ($relativePath in @(& git -c core.quotepath=false -C $repo ls-files --cached --others --exclude-standard -- src server testing tests build.gradle settings.gradle gradle.properties gradle)) {
    $sourcePath=Join-Path $repo $relativePath
    if (Test-Path -LiteralPath $sourcePath -PathType Leaf) { $sourceFiles+=@{path=$relativePath;sha256=(Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()} }
}
$sourceFiles | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputRoot 'source-manifest.json') -Encoding utf8
$metadata=[ordered]@{
    schemaVersion=1;runId=$runId;started=[DateTimeOffset]::Now.ToString('o');phases=@()
    environment=[ordered]@{os=[Environment]::OSVersion.VersionString;powershell=$PSVersionTable.PSVersion.ToString();javaHome=$JavaHome;commit=$commit;workingTreeChanges=$dirty;sourceManifest='source-manifest.json';sourceManifestSha256=(Get-FileHash -LiteralPath (Join-Path $OutputRoot 'source-manifest.json') -Algorithm SHA256).Hash;sourceFiles=$sourceFiles.Count}
    inputs=[ordered]@{modsDirectory=$ModsDirectory;eulaFile=$EulaFile;offline=[bool]$Offline;timeoutSeconds=$TimeoutSeconds;seed=$Seed;requestedPhases=$Phases;dependencies=@()}
    coverage=@('Dedicated backend suite and cross-process persistence restart','Real client with integrated server, Carpet installed','Real client with integrated server, Carpet absent','Dedicated TCP server plus two real Minecraft clients','Three window/GUI sizes, bounds, draft retention and screenshots','Report validators, malformed evidence, process timeout/exit, Unicode and escaping')
    limitations=@('PASS means all declared automated phases completed; it is not a proof that every possible mod state is bug free.','Integrated and dedicated checks invoke real mod handlers/network flows, but do not exercise every operation using mouse/keyboard input.','No arbitrary packet loss, power failure, disk-full, every third-party mod, renderer or operating system is simulated.','Backend random sequences and dedicated scenarios are reproducible with recorded seeds; further seeds increase coverage.')
}
if ($ModsDirectory -and (Test-Path -LiteralPath $ModsDirectory -PathType Container)) {
    foreach ($file in Get-ChildItem -LiteralPath $ModsDirectory -Filter '*.jar' -File) { $metadata.inputs.dependencies+=@{file=$file.Name;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash} }
}
foreach ($definition in $definitions) {
    $metadata.phases += [pscustomobject]@{id=$definition.id;kind=$definition.kind;status='SKIP';complete=$false;processExitCode=$null;timedOut=$false;assertions=0;cases=@();errors=@($(if ($definition.id -in $Phases) {'Not reached yet'} else {'Not selected; run is partial'}));durationSeconds=0}
}
Write-SystemReport $metadata $OutputRoot
$index=0
foreach ($definition in $definitions) {
    if ($definition.id -notin $Phases) { $index++;continue }
    $phaseDir=Join-Path $OutputRoot $definition.id
    $artifactDir=Join-Path $phaseDir 'artifacts'
    $phaseReport=Join-Path $artifactDir 'report.json'
    $start=[DateTimeOffset]::Now
    Write-Host "[system] Starting $($definition.id); output=$phaseDir"
    try {
        $arguments=@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot $definition.script),'-OutputRoot',$artifactDir)
        if ($definition.kind -ne 'selftest') {
            $arguments+=@('-TimeoutSeconds',[string]$TimeoutSeconds)
            if ($JavaHome) { $arguments+=@('-JavaHome',$JavaHome) }
            if ($Offline) { $arguments+='-Offline' }
        }
        if ($definition.id -in @('backend','integrated-carpet','dedicated')) { if ($ModsDirectory) { $arguments+=@('-ModsDirectory',$ModsDirectory) } }
        if ($definition.id -in @('backend','dedicated')) { if ($EulaFile) { $arguments+=@('-EulaFile',$EulaFile) } }
        if ($definition.id -eq 'integrated-vanilla') { $arguments+='-WithoutCarpet' }
        if ($definition.id -eq 'dedicated') { $arguments+=@('-Seed',[string]$Seed) }
        $budget=if ($definition.kind -eq 'selftest') { 180 } else { $TimeoutSeconds*$definition.factor+60 }
        $process=Invoke-SystemProcess -FileName (Join-Path $PSHOME 'pwsh.exe') -Arguments $arguments -WorkingDirectory $repo -LogDirectory (Join-Path $phaseDir 'runner') -TimeoutSeconds $budget
        $phase=Read-SystemPhase -Id $definition.id -Kind $definition.kind -ReportPath $phaseReport -ExitCode $process.exitCode -TimedOut $process.timedOut -RuntimeError $process.error
        $phase | Add-Member -NotePropertyName started -NotePropertyValue $process.started
        $phase | Add-Member -NotePropertyName finished -NotePropertyValue $process.finished
        $phase | Add-Member -NotePropertyName durationSeconds -NotePropertyValue $process.durationSeconds
    } catch {
        $phase=[pscustomobject]@{id=$definition.id;kind=$definition.kind;status='ERROR';complete=$false;processExitCode=$null;timedOut=$false;assertions=0;cases=@();errors=@($_.Exception.ToString());reportPath=$phaseReport;started=$start.ToString('o');finished=[DateTimeOffset]::Now.ToString('o');durationSeconds=([DateTimeOffset]::Now-$start).TotalSeconds}
    }
    $metadata.phases[$index]=$phase
    Write-Host "[system] $($phase.id): $($phase.status), cases=$($phase.cases.Count), assertions=$($phase.assertions)"
    foreach ($message in $phase.errors) { Write-Host "[system] $message" -ForegroundColor Red }
    Write-SystemReport $metadata $OutputRoot
    $index++
}
Write-Host "[system] $($metadata.status): $(Join-Path $OutputRoot 'report.html')"
if ($metadata.status -eq 'FAIL') { exit 1 }
if ($metadata.status -eq 'PARTIAL') { exit 2 }
exit 0
