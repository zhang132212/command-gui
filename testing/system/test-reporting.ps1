#Requires -Version 7.0
[CmdletBinding()]
param([string]$OutputRoot)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ReportLibrary.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (!$OutputRoot) { $OutputRoot=Join-Path $repo ('build/system-report-tests/'+[guid]::NewGuid().ToString('N')) }
$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if ((Test-Path -LiteralPath $OutputRoot) -and @(Get-ChildItem -LiteralPath $OutputRoot -Force).Count -gt 0) { throw 'OutputRoot must be new or empty' }
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$script:cases=@(); $script:checks=0
$fixturePath=Join-Path $OutputRoot 'fixture.json'
function Assert-Check([bool]$Value,[string]$Message) { $script:checks++; if (!$Value) { throw $Message } }
function Test-Scenario([string]$Name,[scriptblock]$Body) {
    $timer=[Diagnostics.Stopwatch]::StartNew(); $errorText=''; $status='PASS'
    try { & $Body } catch { $status='FAIL'; $errorText=$_.Exception.ToString() }
    $script:cases += [ordered]@{group='reporter';name=$Name;status=$status;milliseconds=$timer.ElapsedMilliseconds;error=$errorText}
    Write-Host "[report-test] $status $Name"
}
function New-Fixture {
    [ordered]@{schemaVersion=1;passed=$true;complete=$true;assertions=1;expectedCases=1;cases=@([ordered]@{group='transport';name='中文 <&> </script><script>alert(1)</script>';status='PASS';milliseconds=5;error='';steps=@('connect','请求 → 响应')})}
}
function Read-Fixture($Value,[Nullable[int]]$Code=0,[bool]$TimedOut=$false,[string]$RuntimeError='') {
    $Value | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $fixturePath -Encoding utf8
    Read-SystemPhase -Id dedicated -Kind dedicated -ReportPath $fixturePath -ExitCode $Code -TimedOut $TimedOut -RuntimeError $RuntimeError
}
Test-Scenario 'valid-suite-and-unicode' {
    $result=Read-Fixture (New-Fixture)
    Assert-Check ($result.status -eq 'PASS' -and $result.cases.Count -eq 1 -and $result.assertions -eq 1) 'Valid one-case report rejected'
    Assert-Check ($result.cases[0].name -like '*中文*') 'Unicode lost'
}
foreach ($entry in @(
    @{name='missing-complete';mutate={param($f) $f.Remove('complete')}},
    @{name='false-complete';mutate={param($f) $f.complete=$false}},
    @{name='string-complete';mutate={param($f) $f.complete='True'}},
    @{name='string-passed';mutate={param($f) $f.passed='True'}},
    @{name='empty-cases';mutate={param($f) $f.cases=@()}},
    @{name='object-instead-array';mutate={param($f) $f.cases=$f.cases[0]}},
    @{name='negative-assertions';mutate={param($f) $f.assertions=-1}},
    @{name='zero-assertions';mutate={param($f) $f.assertions=0}},
    @{name='string-assertions';mutate={param($f) $f.assertions='1'}},
    @{name='wrong-expected-count';mutate={param($f) $f.expectedCases=2}},
    @{name='wrong-failed-count';mutate={param($f) $f.failed=1}},
    @{name='wrong-case-status';mutate={param($f) $f.cases[0].status='pass'}},
    @{name='missing-case-name';mutate={param($f) $f.cases[0].name=''}},
    @{name='negative-duration';mutate={param($f) $f.cases[0].milliseconds=-1}},
    @{name='duplicate-case';mutate={param($f) $f.cases+= $f.cases[0];$f.expectedCases=2}},
    @{name='success-with-failed-case';mutate={param($f) $f.cases[0].status='FAIL'}},
    @{name='runner-error-with-passing-cases';mutate={param($f) $f.error='startup failed'}},
    @{name='false-pass-with-passing-cases';mutate={param($f) $f.passed=$false}}
)) {
    $scenario=$entry
    Test-Scenario $scenario.name {
        $fixture=New-Fixture; & $scenario.mutate $fixture | Out-Null
        $result=Read-Fixture $fixture
        Assert-Check ($result.status -eq 'ERROR' -and $result.errors.Count -gt 0) 'Invalid report became green'
    }
}
Test-Scenario 'nonzero-exit-with-passing-report' { Assert-Check ((Read-Fixture (New-Fixture) 9).status -eq 'ERROR') 'Nonzero exit became green' }
Test-Scenario 'no-exit-code' { Assert-Check ((Read-Fixture (New-Fixture) $null).status -eq 'ERROR') 'Null exit became green' }
Test-Scenario 'timeout-with-passing-report' { Assert-Check ((Read-Fixture (New-Fixture) 0 $true).status -eq 'ERROR') 'Timeout became green' }
Test-Scenario 'runtime-error-with-passing-report' { Assert-Check ((Read-Fixture (New-Fixture) 0 $false 'exception').status -eq 'ERROR') 'Exception became green' }
Test-Scenario 'missing-report' { Assert-Check ((Read-SystemPhase -Id missing -Kind dedicated -ReportPath (Join-Path $OutputRoot 'absent.json') -ExitCode 0).status -eq 'ERROR') 'Missing report became green' }
foreach ($raw in @('not json','null','[]','42','{}')) {
    Test-Scenario "invalid-json-$raw" {
        Set-Content -LiteralPath $fixturePath -Value $raw -Encoding utf8
        Assert-Check ((Read-SystemPhase -Id bad -Kind dedicated -ReportPath $fixturePath -ExitCode 0).status -eq 'ERROR') 'Invalid JSON became green'
    }
}
Test-Scenario 'failed-case-remains-visible' {
    $fixture=New-Fixture;$fixture.passed=$false;$fixture.cases[0].status='FAIL';$fixture.cases[0].error="failure <&>`nstack"
    $result=Read-Fixture $fixture
    Assert-Check ($result.status -ne 'PASS' -and $result.cases.Count -eq 1 -and $result.cases[0].status -eq 'FAIL') 'Failed case lost'
}
Test-Scenario 'incomplete-suite-retains-partial-evidence' {
    $fixture=New-Fixture;$fixture.complete=$false;$fixture.passed=$false;$fixture.cases[0].status='FAIL';$fixture.cases[0].error='step 3 timed out'
    $result=Read-Fixture $fixture 1 $true
    Assert-Check ($result.status -eq 'ERROR' -and !$result.complete -and $result.timedOut) 'Partial evidence incorrectly became complete'
    Assert-Check ($result.cases.Count -eq 1 -and $result.cases[0].status -eq 'FAIL' -and $result.cases[0].error -eq 'step 3 timed out') 'Incomplete report lost failed case evidence'
}
Test-Scenario 'mismatched-summary-retains-evidence-but-fails' {
    $fixture=New-Fixture;$fixture.expectedCases=2
    $result=Read-Fixture $fixture
    Assert-Check ($result.status -eq 'ERROR' -and $result.cases.Count -eq 1 -and !$result.complete) 'Count mismatch lost evidence or became green'
}
Test-Scenario 'backend-restart-required-and-count-check' {
    $fixture=New-Fixture
    $phase=[ordered]@{phase='suite';complete=$true;passed=1;failed=0;assertions=1;processExitCode=0;runnerErrors=@();cases=$fixture.cases}
    $wrapper=[ordered]@{passed=$true;phases=@($phase)}
    $wrapper | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $fixturePath -Encoding utf8
    Assert-Check ((Read-SystemPhase -Id backend -Kind backend -ReportPath $fixturePath -ExitCode 0).status -eq 'ERROR') 'Missing restart became green'
    $restart=[ordered]@{phase='restart';complete=$true;passed=1;failed=0;assertions=1;processExitCode=0;runnerErrors=@();cases=$fixture.cases};$wrapper.phases+=$restart
    $wrapper | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $fixturePath -Encoding utf8
    Assert-Check ((Read-SystemPhase -Id backend -Kind backend -ReportPath $fixturePath -ExitCode 0).status -eq 'PASS') 'Valid backend rejected'
    $phase.passed=9
    $wrapper | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $fixturePath -Encoding utf8
    Assert-Check ((Read-SystemPhase -Id backend -Kind backend -ReportPath $fixturePath -ExitCode 0).status -eq 'ERROR') 'Backend count mismatch became green'
}
Test-Scenario 'integrated-environment-and-minimum-coverage' {
    $fixture=New-Fixture;$fixture.Remove('expectedCases');$fixture.cases=@();$fixture.assertions=40;$fixture.carpet=$true
    for ($i=0;$i -lt 22;$i++) { $fixture.cases+=@{name="case-$i";status='PASS'} }
    $wrapper=@{passed=$true;mode='with-carpet';suite=$fixture}
    $wrapper | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $fixturePath -Encoding utf8
    Assert-Check ((Read-SystemPhase -Id integrated-carpet -Kind integrated -ReportPath $fixturePath -ExitCode 0).status -eq 'PASS') 'Valid integrated rejected'
    Assert-Check ((Read-SystemPhase -Id integrated-vanilla -Kind integrated -ReportPath $fixturePath -ExitCode 0).status -eq 'ERROR') 'Wrong environment became green'
    $fixture.cases=@($fixture.cases[0])
    $wrapper | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $fixturePath -Encoding utf8
    Assert-Check ((Read-SystemPhase -Id integrated-carpet -Kind integrated -ReportPath $fixturePath -ExitCode 0).status -eq 'ERROR') 'Truncated integrated became green'
}
Test-Scenario 'html-json-junit-and-skipped-status' {
    $good=Read-Fixture (New-Fixture);$good.id='fixture-good'
    $badFixture=New-Fixture;$badFixture.passed=$false;$badFixture.cases[0].status='FAIL';$badFixture.cases[0].error="failure <&>`n中文"+[char]1
    $bad=Read-Fixture $badFixture 1;$bad.id='fixture-bad'
    $skip=[pscustomobject]@{id='skipped';status='SKIP';cases=@();assertions=0;errors=@('not selected');durationSeconds=0}
    $meta=[ordered]@{schemaVersion=1;runId='selftest';started=[DateTimeOffset]::Now.ToString('o');phases=@($good,$bad,$skip);environment=@{};inputs=@{};coverage=@();limitations=@()}
    $target=Join-Path $OutputRoot 'rendered';New-Item -ItemType Directory -Path $target -Force | Out-Null
    Write-SystemReport $meta $target
    $json=Get-Content -LiteralPath (Join-Path $target 'report.json') -Raw | ConvertFrom-Json
    Assert-Check ($json.status -eq 'FAIL' -and !$json.passed -and $json.summary.casesFailed -eq 1) 'Summary gave wrong status/count'
    [xml]$xml=Get-Content -LiteralPath (Join-Path $target 'junit.xml') -Raw
    Assert-Check ($xml.SelectNodes('//failure').Count -eq 1 -and $xml.SelectNodes('//error').Count -eq 1 -and $xml.SelectNodes('//skipped').Count -eq 1) 'JUnit missing failures/errors/skips'
    foreach ($suite in $xml.testsuites.testsuite) {
        Assert-Check ([int]$suite.tests -eq $suite.SelectNodes('testcase').Count) 'JUnit test count mismatch'
        Assert-Check ([int]$suite.failures -eq $suite.SelectNodes('testcase/failure').Count) 'JUnit failure count mismatch'
        Assert-Check ([int]$suite.errors -eq $suite.SelectNodes('testcase/error').Count) 'JUnit error count mismatch'
    }
    $html=Get-Content -LiteralPath (Join-Path $target 'report.html') -Raw
    Assert-Check (!$html.Contains('</script><script>alert(1)</script>')) 'Unsafe HTML embedding'
    Assert-Check ($html.Contains('\u003c/script\u003e') -and $html.Contains('中文')) 'Escaping or Unicode lost'
    Assert-Check (!$html.Contains('https://') -and !$html.Contains('__REPORT_DATA__')) 'External dependency or placeholder remains'
    $meta.phases=@($good,$skip);Write-SystemReport $meta $target
    Assert-Check ($meta.status -eq 'PARTIAL' -and !$meta.passed) 'Skipped phase became all green'
    $meta.phases=@();Write-SystemReport $meta $target
    Assert-Check ($meta.status -eq 'PARTIAL') 'Empty run became all green'
}
Test-Scenario 'trace-valid-packet-counts-and-key-events' {
    $target=Join-Path $OutputRoot 'trace-fixtures';$trace=Join-Path $target 'server/events.jsonl'
    New-Item -ItemType Directory -Path (Split-Path $trace) -Force | Out-Null
    $rows=@(
        @{seq=1;utc='2026-09-24T00:00:00Z';elapsedNanos=1;role='server';event='process.start';data=@{java='25'}},
        @{seq=2;utc='2026-09-24T00:00:01Z';elapsedNanos=2;role='server';event='packet';data=@{direction='receive';type='command-gui-server:action';memoryConnection=$false;payload=@{text='中文'}}},
        @{seq=3;utc='2026-09-24T00:00:02Z';elapsedNanos=3;role='server';event='packet';data=@{direction='receive';type='command-gui-server:action';memoryConnection=$false;payload=@{text='second'}}},
        @{seq=4;utc='2026-09-24T00:00:03Z';elapsedNanos=4;role='server';event='scheduler.execute';data=@{command='/say 中文';runtime='m'}},
        @{seq=5;utc='2026-09-24T00:00:04Z';elapsedNanos=5;role='server';event='server.finished';data=@{passed=$true;complete=$true}}
    )
    ($rows | ForEach-Object { $_ | ConvertTo-Json -Depth 10 -Compress }) | Set-Content -LiteralPath $trace -Encoding utf8
    $result=Read-SystemTraces -OutputRoot $OutputRoot -TraceRoot $target
    Assert-Check ($result.status -eq 'PASS' -and $result.lines -eq 5 -and $result.parsedLines -eq 5) 'Valid trace rejected'
    Assert-Check ($result.packetStats.Count -eq 1 -and $result.packetStats[0].count -eq 2 -and $result.packetStats[0].tcp -eq 2) 'Packet direction/type counts wrong'
    Assert-Check ($result.timeline.Count -eq 3 -and @($result.timeline | Where-Object event -EQ 'scheduler.execute').Count -eq 1) 'Command execution timeline missing'
    Assert-Check ((Read-SystemTraces -OutputRoot $OutputRoot -TraceRoot $target -Required).issues.Count -eq 2) 'Missing required actor traces ignored'
}
Test-Scenario 'trace-failure-and-sequence-errors' {
    $target=Join-Path $OutputRoot 'trace-failure';$trace=Join-Path $target 'server/events.jsonl'
    New-Item -ItemType Directory -Path (Split-Path $trace) -Force | Out-Null
    $rows=@(
        @{seq=1;utc='2026-09-24T00:00:00Z';elapsedNanos=10;role='server';event='server.failure';data='simulated server error'},
        @{seq=3;utc='2026-09-24T00:00:01Z';elapsedNanos=9;role='server';event='stage.fail';data=@{error='simulated stage error'}},
        @{seq=3;utc='2026-09-24T00:00:02Z';elapsedNanos=12;role='server';event='server.assert';data=@{passed=$false;name='assertion'}}
    )
    ($rows | ForEach-Object { $_ | ConvertTo-Json -Depth 10 -Compress }) | Set-Content -LiteralPath $trace -Encoding utf8
    $result=Read-SystemTraces -OutputRoot $OutputRoot -TraceRoot $target
    Assert-Check ($result.status -eq 'ERROR' -and $result.failureEventCount -eq 3 -and $result.failureEvents.Count -eq 3) 'Failure events lost'
    Assert-Check (@($result.issues | Where-Object code -EQ 'sequence-gap').Count -eq 2) 'Missing or repeated seq not detected'
    Assert-Check (@($result.issues | Where-Object code -EQ 'nonmonotonic-time').Count -eq 1) 'Monotonic time regression not detected'
}
Test-Scenario 'trace-truncated-tail-and-invalid-json' {
    $target=Join-Path $OutputRoot 'trace-truncated';$trace=Join-Path $target 'server/events.jsonl'
    New-Item -ItemType Directory -Path (Split-Path $trace) -Force | Out-Null
    [IO.File]::WriteAllText($trace,"not-json`n{`"seq`":2",[Text.UTF8Encoding]::new($false))
    $result=Read-SystemTraces -OutputRoot $OutputRoot -TraceRoot $target
    Assert-Check ($result.status -eq 'ERROR' -and $result.lines -eq 2 -and $result.parsedLines -eq 0) 'Bad or truncated lines ignored'
    Assert-Check (@($result.issues | Where-Object code -EQ 'unterminated-tail').Count -eq 1 -and @($result.issues | Where-Object code -EQ 'invalid-event').Count -eq 2) 'Tail truncation/error evidence missing'
}
Test-Scenario 'trace-invalid-utf8-and-bounded-html-data' {
    $target=Join-Path $OutputRoot 'trace-size';$trace=Join-Path $target 'server/events.jsonl'
    New-Item -ItemType Directory -Path (Split-Path $trace) -Force | Out-Null
    [IO.File]::WriteAllBytes($trace,[byte[]]@(0xFF,0xFE,0xFF,10))
    Assert-Check ((Read-SystemTraces -OutputRoot $OutputRoot -TraceRoot $target).status -eq 'ERROR') 'Invalid UTF-8 accepted'
    $rows=@();for ($i=1;$i -le 50;$i++) { $rows+=@{seq=$i;utc='2026-09-24T00:00:00Z';elapsedNanos=$i;role='server';event='stage.pass';data=@{name=('x'*3000)}} }
    $rows+=@{seq=51;utc='2026-09-24T00:00:01Z';elapsedNanos=51;role='server';event='stage.fail';data=@{error='failure must survive cap'}}
    ($rows | ForEach-Object { $_ | ConvertTo-Json -Depth 10 -Compress }) | Set-Content -LiteralPath $trace -Encoding utf8
    $result=Read-SystemTraces -OutputRoot $OutputRoot -TraceRoot $target -MaxTimelineEvents 10
    Assert-Check ($result.status -eq 'FAIL' -and $result.timeline.Count -le 10 -and $result.keyEventCount -eq 51 -and $result.timelineOmitted -eq 41) 'Timeline bounds/counts wrong'
    Assert-Check (@($result.timeline | Where-Object failed -EQ $true).Count -eq 1 -and $result.timeline[0].summary.Length -lt 2200) 'Failure lost due to cap or excerpt unbounded'
}
Test-Scenario 'process-exit-and-raw-utf8-capture' {
    $child=Join-Path $OutputRoot 'unicode-child.ps1'
    "[Console]::OutputEncoding=[Text.UTF8Encoding]::new(`$false);[Console]::Write('中文-stdout');[Console]::Error.Write('中文-stderr');exit 7" | Set-Content -LiteralPath $child -Encoding utf8
    $result=Invoke-SystemProcess -FileName (Join-Path $PSHOME 'pwsh.exe') -Arguments @('-NoProfile','-File',$child) -WorkingDirectory $repo -LogDirectory (Join-Path $OutputRoot 'child-exit') -TimeoutSeconds 20
    Assert-Check ($result.exitCode -eq 7 -and !$result.timedOut) 'Wrong process exit capture'
    Assert-Check ((Get-Content -LiteralPath $result.stdout -Raw -Encoding utf8) -ceq '中文-stdout') 'stdout encoding/content lost'
    Assert-Check ((Get-Content -LiteralPath $result.stderr -Raw -Encoding utf8) -ceq '中文-stderr') 'stderr encoding/content lost'
}
Test-Scenario 'invalid-xml-codepoints-and-surrogate-pairs' {
    $value='中文'+[char]1+[char]0xFFFF+[char]0xD800+[char]0xDC00+[char]0xD800
    $safe=ConvertTo-XmlSafe $value
    Assert-Check ([Xml.XmlConvert]::VerifyXmlChars($safe) -ceq $safe) 'Sanitized output is still invalid XML'
    Assert-Check ($safe.Contains([string][char]0xD800+[char]0xDC00)) 'Valid Unicode surrogate pair was lost'
    Assert-Check ([regex]::Matches($safe,[string][char]0xFFFD).Count -eq 3) 'Invalid characters were not replaced'
}
Test-Scenario 'process-timeout-and-cleanup' {
    $result=Invoke-SystemProcess -FileName (Join-Path $PSHOME 'pwsh.exe') -Arguments @('-NoProfile','-Command','Start-Sleep -Seconds 20') -WorkingDirectory $repo -LogDirectory (Join-Path $OutputRoot 'child-timeout') -TimeoutSeconds 0.5
    Assert-Check ($result.timedOut -and $result.error -like '*Timeout*' -and $result.durationSeconds -lt 15) 'Timeout enforcement/cleanup failed'
}
Test-Scenario 'process-launch-failure' {
    $result=Invoke-SystemProcess -FileName (Join-Path $OutputRoot 'missing.exe') -Arguments @() -WorkingDirectory $repo -LogDirectory (Join-Path $OutputRoot 'child-launch') -TimeoutSeconds 1
    Assert-Check ($null -eq $result.exitCode -and $result.error.Length -gt 0) 'Launch failure lost'
}
Test-Scenario 'existing-backend-reporter-regressions' {
    $result=Invoke-SystemProcess -FileName (Join-Path $PSHOME 'pwsh.exe') -Arguments @('-NoProfile','-File',(Join-Path $repo 'testing/backend/test-runner-reports.ps1')) -WorkingDirectory $repo -LogDirectory (Join-Path $OutputRoot 'backend-reporter') -TimeoutSeconds 60
    $log=Get-Content -LiteralPath $result.stdout -Raw -Encoding utf8
    Assert-Check ($result.exitCode -eq 0 -and $log -match 'Runner report regression checks passed: (\d+)') 'Backend reporter selftest failed'
    Assert-Check ([int]$Matches[1] -ge 112) 'Backend selftest coverage fell below baseline'
}
$failed=@($script:cases | Where-Object status -EQ 'FAIL').Count
$report=[ordered]@{schemaVersion=1;passed=$failed -eq 0;complete=$true;assertions=$script:checks;expectedCases=$script:cases.Count;cases=$script:cases}
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $OutputRoot 'report.json') -Encoding utf8
Write-Host "SYSTEM_REPORT_TESTS_COMPLETE cases=$($script:cases.Count) assertions=$($script:checks) failed=$failed"
if ($failed -gt 0) { exit 1 };exit 0
