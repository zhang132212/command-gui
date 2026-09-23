#Requires -Version 7.0
# Offline regression checks: no Gradle, Minecraft, network, or accepted EULA required.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'RunnerReports.ps1')
$output = Join-Path $PSScriptRoot ("../../build/backend-runner-tests/" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -Force | Out-Null
$reportFile = Join-Path $output 'phase.json'
$junitFile = Join-Path $output 'junit.xml'
$checks = 0
function Assert-Report([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
    $script:checks++
}
function New-PhaseFixture {
    @{ phase='suite'; complete=$true; passed=1; failed=0; assertions=1; cases=@(@{group='transport';name='unicode 中文 <&>';status='PASS';error=''}) }
}
function Read-Fixture($Fixture, [int]$Code = 0, [string]$RuntimeError = '') {
    $Fixture | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $reportFile -Encoding utf8
    Read-BackendPhaseResult -ReportPath $reportFile -Phase 'suite' -ExitCode $Code -RuntimeError $RuntimeError
}
function Assert-JUnit($Phase, [int]$Failures, [int]$Errors, [int]$Cases) {
    Write-BackendJUnit -Metadata ([ordered]@{phases=@($Phase)}) -Path $junitFile
    [xml]$xml = Get-Content -LiteralPath $junitFile -Raw
    Assert-Report ($xml.SelectNodes('//failure').Count -eq $Failures) 'JUnit failure nodes disagree'
    Assert-Report ($xml.SelectNodes('//error').Count -eq $Errors) 'JUnit error nodes disagree'
    Assert-Report ($xml.SelectNodes('//testcase').Count -eq $Cases) 'JUnit testcase nodes disagree'
    Assert-Report ([int]$xml.testsuites.testsuite.tests -eq $Cases) 'JUnit tests attribute disagrees'
    Assert-Report ([int]$xml.testsuites.testsuite.errors -eq $Errors) 'JUnit errors attribute disagrees'
    Assert-Report ([int]$xml.testsuites.testsuite.failures -eq $Failures) 'JUnit failures attribute disagrees'
}

$passed = Read-Fixture (New-PhaseFixture)
Assert-Report ($passed.complete -and $passed.runnerErrors.Count -eq 0) 'Valid complete phase rejected'
Assert-JUnit $passed 0 0 1
Assert-Report (([xml](Get-Content -LiteralPath $junitFile -Raw)).testsuites.testsuite.testcase.name -eq 'unicode 中文 <&>') 'JUnit lost Unicode or XML escaping'

$fixture = New-PhaseFixture
$fixture.failed=1; $fixture.passed=0; $fixture.cases[0].status='FAIL'; $fixture.cases[0].error="failure <&>`nstack"
$failed = Read-Fixture $fixture
Assert-Report ($failed.failed -eq 1 -and $failed.runnerErrors.Count -eq 0) 'Assertion failures classified incorrectly'
Assert-JUnit $failed 1 0 1

$exited = Read-Fixture (New-PhaseFixture) 17
Assert-Report ($exited.processExitCode -eq 17 -and $exited.runnerErrors.Count -gt 0) 'Nonzero exit hidden by complete report'
Assert-JUnit $exited 0 1 2
$fixture = New-PhaseFixture; $fixture.complete=$false
$incomplete = Read-Fixture $fixture
Assert-Report (!$incomplete.complete -and $incomplete.cases.Count -eq 1) 'Incomplete phase lost completed cases'
Assert-JUnit $incomplete 0 1 2
$timedOut = Read-Fixture $fixture -1 'Timed out after 30 seconds'
Assert-Report ($timedOut.runnerErrors -contains 'Timed out after 30 seconds') 'Timeout error not retained'
Assert-JUnit $timedOut 0 1 2

foreach ($mutation in @(
    { param($f) $f.phase='restart' },
    { param($f) $f.Remove('complete') },
    { param($f) $f.complete='true' },
    { param($f) $f.cases=$null },
    { param($f) $f.cases[0].status='SKIP' },
    { param($f) $f.passed=99 },
    { param($f) $f.assertions=-1 },
    { param($f) $f.assertions=0 },
    { param($f) $f.cases=@(); $f.passed=0 }
)) {
    $fixture = New-PhaseFixture; & $mutation $fixture
    $invalid = Read-Fixture $fixture
    Assert-Report (!$invalid.complete -and $invalid.runnerErrors.Count -gt 0) 'Malformed report accepted'
    Assert-JUnit $invalid 0 1 1
}
foreach ($raw in @('null','[]','{"phase":')) {
    Set-Content -LiteralPath $reportFile -Value $raw -Encoding utf8
    $invalid = Read-BackendPhaseResult -ReportPath $reportFile -Phase 'suite' -ExitCode 0
    Assert-Report (!$invalid.complete -and $invalid.runnerErrors.Count -gt 0) 'Invalid JSON root accepted'
}
$missing = Read-BackendPhaseResult -ReportPath (Join-Path $output 'missing.json') -Phase 'suite' -ExitCode 1 -RuntimeError 'Could not start process'
Assert-Report ($missing.runnerErrors -contains 'Could not start process') 'Startup error not retained'
Assert-JUnit $missing 0 1 1
Write-BackendJUnit -Metadata ([ordered]@{phases=@($passed)}) -Path $junitFile -SkipRestart
[xml]$xml = Get-Content -LiteralPath $junitFile -Raw
Assert-Report ($xml.SelectNodes('//skipped').Count -eq 1 -and $xml.SelectNodes('//error').Count -eq 0) 'SkipRestart must remain explicitly partial'
Assert-Report ([int]$xml.testsuites.testsuite[1].skipped -eq 1) 'Skipped suite count missing'
Write-BackendJUnit -Metadata ([ordered]@{phases=@();error='Runner crashed'}) -Path $junitFile
[xml]$xml = Get-Content -LiteralPath $junitFile -Raw
Assert-Report ($xml.SelectNodes('//error').Count -eq 1 -and [int]$xml.testsuites.testsuite.errors -eq 1) 'Fatal runner failure not visible in JUnit'
Write-Host "Runner report regression checks passed: $checks; output: $output"
