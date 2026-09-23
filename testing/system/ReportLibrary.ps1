# Shared report validation and process supervision. No Minecraft or Gradle side effects on import.
Set-StrictMode -Version 3.0
. (Join-Path $PSScriptRoot 'TraceAnalysis.ps1')

function Get-ReportField($Object, [string]$Name, $Default = $null) {
    if ($null -eq $Object) { return ,$Default }
    if ($Object -is [Collections.IDictionary]) { if ($Object.Contains($Name)) { return ,$Object[$Name] } }
    elseif ($null -ne $Object.PSObject.Properties[$Name]) { return ,$Object.$Name }
    return ,$Default
}

function Assert-ReportInteger($Value, [string]$Name, [int]$Minimum = 0) {
    if (($Value -isnot [int] -and $Value -isnot [long]) -or $Value -lt $Minimum) { throw "$Name must be an integer >= $Minimum" }
}

function Read-CaseSuite($Suite, [string]$Prefix = '', [int]$MinimumCases = 1, [int]$MinimumAssertions = 1) {
    if ($null -eq $Suite -or $Suite -isnot [pscustomobject]) { throw 'Suite must be a JSON object' }
    $complete=Get-ReportField $Suite 'complete'
    if ($complete -isnot [bool] -or !$complete) { throw 'Suite did not complete (complete must be boolean true)' }
    $cases = Get-ReportField $Suite 'cases'
    if ($cases -isnot [array] -or $cases.Count -lt $MinimumCases) { throw "Suite must contain at least $MinimumCases cases" }
    $assertions = Get-ReportField $Suite 'assertions'
    Assert-ReportInteger $assertions 'assertions' $MinimumAssertions
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $normalized = @()
    foreach ($case in $cases) {
        $name = Get-ReportField $case 'name'
        $status = Get-ReportField $case 'status'
        $group = Get-ReportField $case 'group' 'flow'
        if ($name -isnot [string] -or [string]::IsNullOrWhiteSpace($name) -or $group -isnot [string] -or [string]::IsNullOrWhiteSpace($group) -or $status -isnot [string] -or $status -cnotin @('PASS','FAIL')) { throw 'Invalid case name/group/status' }
        if (!$names.Add("${group}/${name}")) { throw "Duplicate case: $group/$name" }
        $milliseconds = Get-ReportField $case 'milliseconds' 0
        if ($milliseconds -isnot [ValueType] -or $milliseconds -is [bool] -or [double]$milliseconds -lt 0 -or [double]::IsNaN([double]$milliseconds) -or [double]::IsInfinity([double]$milliseconds)) { throw "Invalid case duration: $name" }
        $steps=Get-ReportField $case 'steps' @()
        $normalized += [pscustomobject]@{ group="$Prefix$group"; name=$name; status=$status; milliseconds=$milliseconds; error=[string](Get-ReportField $case 'error' ''); steps=@($steps) }
    }
    $passed = @($normalized | Where-Object status -CEQ 'PASS').Count
    $failed = @($normalized | Where-Object status -CEQ 'FAIL').Count
    $declaredPassed = Get-ReportField $Suite 'passed'
    if ($declaredPassed -is [bool]) {
        if ($declaredPassed -ne ($failed -eq 0)) { throw 'passed flag disagrees with case statuses' }
    } elseif ($null -ne $declaredPassed) {
        Assert-ReportInteger $declaredPassed 'passed'
        if ($declaredPassed -ne $passed) { throw 'passed count disagrees with case statuses' }
    } else { throw 'Suite missing passed field' }
    $declaredFailed = Get-ReportField $Suite 'failed'
    if ($null -ne $declaredFailed) {
        Assert-ReportInteger $declaredFailed 'failed'
        if ($declaredFailed -ne $failed) { throw 'failed count disagrees with case statuses' }
    }
    $expected = Get-ReportField $Suite 'expectedCases'
    if ($null -ne $expected) {
        Assert-ReportInteger $expected 'expectedCases' 1
        if ($expected -ne $cases.Count) { throw 'expectedCases disagrees with case count' }
    }
    [pscustomobject]@{ cases=@($normalized); assertions=$assertions }
}

# Only for displaying partial evidence after validation has ALREADY failed.
# This deliberately does not establish completeness, expected counts or a PASS result.
function Read-PartialSuiteEvidence($Suite, [string]$Prefix = '') {
    $cases=Get-ReportField $Suite 'cases'
    if ($cases -isnot [array] -or $cases.Count -eq 0) { return [pscustomobject]@{cases=@();assertions=0} }
    $failed=@($cases | Where-Object { (Get-ReportField $_ 'status') -ceq 'FAIL' }).Count
    $reportedAssertions=Get-ReportField $Suite 'assertions'
    if (($reportedAssertions -isnot [int] -and $reportedAssertions -isnot [long]) -or $reportedAssertions -lt 0) { $reportedAssertions=0 }
    $displayOnly=[pscustomobject]@{cases=$cases;complete=$true;passed=($failed -eq 0);assertions=[Math]::Max(1,$reportedAssertions)}
    $evidence=Read-CaseSuite $displayOnly $Prefix
    [pscustomobject]@{cases=$evidence.cases;assertions=$reportedAssertions}
}

function Read-SystemPhase {
    param([string]$Id, [string]$Kind, [string]$ReportPath, [Nullable[int]]$ExitCode, [bool]$TimedOut = $false, [string]$RuntimeError = '')
    $errors = [Collections.Generic.List[string]]::new()
    $result = [ordered]@{ id=$Id; kind=$Kind; status='ERROR'; complete=$false; processExitCode=$ExitCode; timedOut=$TimedOut; assertions=0; cases=@(); errors=@(); reportPath=$ReportPath }
    if ($RuntimeError) { $errors.Add($RuntimeError) }
    if ($TimedOut) { $errors.Add('Process exceeded the time limit') }
    if ($null -eq $ExitCode) { $errors.Add('Process did not return an exit code') }
    elseif ($ExitCode -ne 0) { $errors.Add("Process exited with code $ExitCode") }
    $report=$null
    try {
        if (!(Test-Path -LiteralPath $ReportPath -PathType Leaf)) { throw 'Result report is missing' }
        $report = Get-Content -LiteralPath $ReportPath -Raw -Encoding utf8 | ConvertFrom-Json -ErrorAction Stop
        if ($null -eq $report -or $report -isnot [pscustomobject]) { throw 'Result report must be a JSON object' }
        if ((Get-ReportField $report 'passed') -isnot [bool]) { throw 'Runner passed must be a boolean' }
        $runnerError = Get-ReportField $report 'error'
        if ($runnerError) { $errors.Add([string]$runnerError) }
        $suites = @()
        if ($Kind -eq 'backend') {
            $phases = Get-ReportField $report 'phases'
            if ($phases -isnot [array] -or $phases.Count -ne 2) { throw 'Backend must contain both suite and restart reports' }
            if ($phases[0].phase -cne 'suite' -or $phases[1].phase -cne 'restart') { throw 'Backend phase order must be suite,restart' }
            foreach ($phase in $phases) {
                $phaseErrors=Get-ReportField $phase 'runnerErrors' @()
                if (@($phaseErrors).Count -gt 0) { throw "Backend phase runnerErrors: $($phaseErrors -join '; ')" }
                if ((Get-ReportField $phase 'processExitCode') -cne 0) { throw "Backend phase $($phase.phase) did not exit successfully" }
                $suites += Read-CaseSuite $phase "$($phase.phase)/"
            }
        } elseif ($Kind -eq 'integrated') {
            $suite = Get-ReportField $report 'suite'
            $suites += Read-CaseSuite $suite '' 22 40
            $expectedCarpet = $Id -eq 'integrated-carpet'
            if ((Get-ReportField $suite 'carpet') -isnot [bool] -or $suite.carpet -ne $expectedCarpet) { throw 'Carpet environment does not match requested phase' }
            if ((Get-ReportField $report 'mode') -cne $(if ($expectedCarpet) { 'with-carpet' } else { 'without-carpet' })) { throw 'Integrated mode metadata mismatch' }
        } else {
            $suites += Read-CaseSuite $report
        }
        foreach ($suite in $suites) { $result.cases += $suite.cases; $result.assertions += $suite.assertions }
        $result.complete = $true
        if ($report.passed -cne $true) { $errors.Add('Runner reported passed=false') }
    } catch {
        $errors.Add($_.Exception.Message)
        # Keep completed/failed cases visible on an interrupted run. Validation errors
        # remain authoritative; this recovery can never turn a failed phase green.
        if ($null -ne $report -and $result.cases.Count -eq 0) {
            $partialSuites=@()
            if ($Kind -eq 'backend') {
                $rawPhases=Get-ReportField $report 'phases' @()
                foreach ($rawPhase in $rawPhases) { $partialSuites+=@{suite=$rawPhase;prefix=([string](Get-ReportField $rawPhase 'phase' 'unknown')+'/')} }
            } elseif ($Kind -eq 'integrated') { $partialSuites+=@{suite=(Get-ReportField $report 'suite');prefix=''} }
            else { $partialSuites+=@{suite=$report;prefix=''} }
            foreach ($partial in $partialSuites) {
                try {
                    $evidence=Read-PartialSuiteEvidence $partial.suite $partial.prefix
                    $result.cases+=@($evidence.cases);$result.assertions+=$evidence.assertions
                } catch { $errors.Add("Partial case evidence is invalid: $($_.Exception.Message)") }
            }
        }
    }
    $result.errors = @($errors.ToArray())
    if ($errors.Count -gt 0) { $result.status = 'ERROR' }
    elseif (@($result.cases | Where-Object status -CEQ 'FAIL').Count -gt 0) { $result.status = 'FAIL' }
    else { $result.status = 'PASS' }
    [pscustomobject]$result
}

function Invoke-SystemProcess {
    param([string]$FileName, [string[]]$Arguments, [string]$WorkingDirectory, [string]$LogDirectory, [double]$TimeoutSeconds, [hashtable]$Environment = @{})
    New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
    $start = [DateTimeOffset]::Now
    $result = [ordered]@{ started=$start.ToString('o'); finished=$null; durationSeconds=0; exitCode=$null; timedOut=$false; error=''; stdout=(Join-Path $LogDirectory 'stdout.log'); stderr=(Join-Path $LogDirectory 'stderr.log') }
    [ordered]@{ executable=$FileName; arguments=$Arguments; workingDirectory=$WorkingDirectory; timeoutSeconds=$TimeoutSeconds; environment=$Environment; started=$result.started } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $LogDirectory 'command.json') -Encoding utf8
    $process = $null; $outFile = $null; $errFile = $null; $outCopy = $null; $errCopy = $null
    try {
        $psi = [Diagnostics.ProcessStartInfo]::new()
        $psi.FileName=$FileName; $psi.WorkingDirectory=$WorkingDirectory; $psi.UseShellExecute=$false; $psi.CreateNoWindow=$true
        $psi.RedirectStandardOutput=$true; $psi.RedirectStandardError=$true; $psi.RedirectStandardInput=$true
        foreach ($key in $Environment.Keys) { $psi.Environment[$key] = $Environment[$key] }
        foreach ($argument in $Arguments) { $psi.ArgumentList.Add($argument) }
        $outFile = [IO.File]::Create($result.stdout); $errFile = [IO.File]::Create($result.stderr)
        $process=[Diagnostics.Process]::new(); $process.StartInfo=$psi
        [void]$process.Start()
        $process.StandardInput.Close()
        # Copy raw bytes: original UTF-8 and stream boundaries survive without re-encoding.
        $outCopy=$process.StandardOutput.BaseStream.CopyToAsync($outFile)
        $errCopy=$process.StandardError.BaseStream.CopyToAsync($errFile)
        $clock=[Diagnostics.Stopwatch]::StartNew(); $last=0
        while (!$process.WaitForExit(250)) {
            if ($clock.Elapsed.TotalSeconds -ge $TimeoutSeconds) { $result.timedOut=$true; throw "Timeout after $TimeoutSeconds seconds" }
            if ($clock.Elapsed.TotalSeconds -ge $last + 20) { $last=[int]$clock.Elapsed.TotalSeconds; Write-Host "[system] $([IO.Path]::GetFileName($LogDirectory)) running (${last}s)" }
        }
        $result.exitCode=$process.ExitCode
    } catch { $result.error=$_.Exception.Message }
    finally {
        if ($null -ne $outCopy) {
            try {
                if (!$process.HasExited) { $process.Kill($true); [void]$process.WaitForExit(10000) }
                if ($process.HasExited) { $result.exitCode=$process.ExitCode }
                if (![Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($outCopy,$errCopy),10000)) { throw 'Log stream drain timed out' }
            } catch { $result.error += "; cleanup: $($_.Exception.Message)" }
        }
        if ($null -ne $outFile) { $outFile.Dispose() }; if ($null -ne $errFile) { $errFile.Dispose() }; if ($null -ne $process) { $process.Dispose() }
        $result.finished=[DateTimeOffset]::Now.ToString('o'); $result.durationSeconds=[Math]::Round(([DateTimeOffset]::Now-$start).TotalSeconds,3)
    }
    [pscustomobject]$result
}

function Get-SystemEvidence([string]$Root) {
    $items=@()
    foreach ($file in Get-ChildItem -LiteralPath $Root -Recurse -File) {
        if ($file.Extension -notin @('.log','.json','.jsonl','.xml','.png','.jpg','.md','.txt','.html')) { continue }
        $relative=[IO.Path]::GetRelativePath($Root,$file.FullName).Replace('\','/')
        if ($relative -in @('report.json','report.html','junit.xml')) { continue }
        if ($relative -match '/(?:assets|libraries|saves|backend-test-world|system-test-world|world)/') { continue }
        $url=($relative.Split('/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
        $hash='';$hashError=''
        try { $hash=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
        catch { $hashError=$_.Exception.Message }
        $items += [pscustomobject]@{ path=$relative; url=$url; bytes=$file.Length; sha256=$hash; hashError=$hashError; type=$(if ($file.Extension -in @('.png','.jpg')) {'image'} elseif ($file.Extension -in @('.log','.jsonl')) {'log'} else {'report'}) }
    }
    $items
}

function ConvertTo-XmlSafe([string]$Text) {
    $buffer=[Text.StringBuilder]::new()
    for ($index=0;$index -lt $Text.Length;$index++) {
        $character=$Text[$index]
        if ([char]::IsHighSurrogate($character) -and $index+1 -lt $Text.Length -and [char]::IsLowSurrogate($Text[$index+1])) {
            [void]$buffer.Append($character);[void]$buffer.Append($Text[++$index])
        } elseif ([Xml.XmlConvert]::IsXmlChar($character)) { [void]$buffer.Append($character) }
        else { [void]$buffer.Append([char]0xFFFD) }
    }
    $buffer.ToString()
}

function Write-SystemReport {
    param([Collections.IDictionary]$Metadata, [string]$OutputRoot)
    $phases=@($Metadata.phases)
    $dedicated=@($phases | Where-Object id -EQ 'dedicated')
    $traceRequired=$dedicated.Count -gt 0 -and $dedicated[0].status -ne 'SKIP'
    $Metadata['traceAnalysis']=Read-SystemTraces -OutputRoot $OutputRoot -Required:$traceRequired
    if ($traceRequired -and $Metadata.traceAnalysis.status -in @('ERROR','FAIL')) {
        $message="Structured trace validation: $($Metadata.traceAnalysis.status), issues=$($Metadata.traceAnalysis.issues.Count), failureEvents=$($Metadata.traceAnalysis.failureEventCount)"
        if ($dedicated[0].status -eq 'PASS') { $dedicated[0].status='ERROR' }
        $dedicated[0].errors=@($dedicated[0].errors | Where-Object { $_ -notlike 'Structured trace validation:*' })+@($message)
    }
    $failedPhases=@($phases | Where-Object status -In @('FAIL','ERROR')).Count
    $skipped=@($phases | Where-Object status -EQ 'SKIP').Count
    $cases=@($phases | ForEach-Object { $_.cases })
    $Metadata['finished']=[DateTimeOffset]::Now.ToString('o')
    $Metadata['status']=if ($failedPhases -gt 0) { 'FAIL' } elseif ($skipped -gt 0 -or $phases.Count -eq 0) { 'PARTIAL' } else { 'PASS' }
    $Metadata['passed']=$Metadata.status -eq 'PASS'
    $assertions=0; foreach ($phase in $phases) { $assertions += $phase.assertions }
    $Metadata['summary']=[ordered]@{ phases=$phases.Count; phasesPassed=@($phases | Where-Object status -EQ 'PASS').Count; phasesFailed=$failedPhases; phasesSkipped=$skipped; cases=$cases.Count; casesPassed=@($cases | Where-Object status -EQ 'PASS').Count; casesFailed=@($cases | Where-Object status -EQ 'FAIL').Count; assertions=$assertions }
    $Metadata['evidence']=@(Get-SystemEvidence $OutputRoot)
    $json=$Metadata | ConvertTo-Json -Depth 40
    [IO.File]::WriteAllText((Join-Path $OutputRoot 'report.json'),$json,[Text.UTF8Encoding]::new($false))
    $settings=[Xml.XmlWriterSettings]::new(); $settings.Indent=$true; $settings.Encoding=[Text.UTF8Encoding]::new($false)
    $xml=[Xml.XmlWriter]::Create((Join-Path $OutputRoot 'junit.xml'),$settings)
    try {
        $xml.WriteStartDocument(); $xml.WriteStartElement('testsuites')
        foreach ($phase in $phases) {
            $isError=$phase.status -eq 'ERROR'; $isSkip=$phase.status -eq 'SKIP'
            $xml.WriteStartElement('testsuite'); $xml.WriteAttributeString('name',$phase.id)
            $xml.WriteAttributeString('tests',[string](@($phase.cases).Count+[int]$isError+[int]$isSkip))
            $xml.WriteAttributeString('failures',[string]@($phase.cases | Where-Object status -EQ 'FAIL').Count)
            $xml.WriteAttributeString('errors',[string][int]$isError); $xml.WriteAttributeString('skipped',[string][int]$isSkip)
            $xml.WriteAttributeString('time',([double](Get-ReportField $phase 'durationSeconds' 0)).ToString('0.###',[Globalization.CultureInfo]::InvariantCulture))
            foreach ($case in $phase.cases) {
                $xml.WriteStartElement('testcase'); $xml.WriteAttributeString('classname',(ConvertTo-XmlSafe $case.group)); $xml.WriteAttributeString('name',(ConvertTo-XmlSafe $case.name))
                $xml.WriteAttributeString('time',([double]$case.milliseconds/1000).ToString('0.###',[Globalization.CultureInfo]::InvariantCulture))
                if ($case.status -eq 'FAIL') { $xml.WriteElementString('failure',(ConvertTo-XmlSafe $case.error)) }
                $xml.WriteEndElement()
            }
            if ($isError -or $isSkip) {
                $xml.WriteStartElement('testcase'); $xml.WriteAttributeString('classname','runner'); $xml.WriteAttributeString('name','phase-completion')
                $xml.WriteElementString($(if ($isError) {'error'} else {'skipped'}),(ConvertTo-XmlSafe ($phase.errors -join "`n"))); $xml.WriteEndElement()
            }
            $xml.WriteEndElement()
        }
        $xml.WriteEndElement(); $xml.WriteEndDocument()
    } finally { $xml.Dispose() }
    $safeJson=$json.Replace('<','\u003c').Replace('>','\u003e').Replace('&','\u0026').Replace([string][char]0x2028,'\u2028').Replace([string][char]0x2029,'\u2029')
    $template=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'report.template.html') -Raw -Encoding utf8
    [IO.File]::WriteAllText((Join-Path $OutputRoot 'report.html'),$template.Replace('__REPORT_DATA__',$safeJson),[Text.UTF8Encoding]::new($false))
}
