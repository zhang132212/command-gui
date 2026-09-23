# Shared by the real runner and the offline report regression tests.
function Read-BackendPhaseResult {
    param([string]$ReportPath, [string]$Phase, [Nullable[int]]$ExitCode, [string]$RuntimeError)
    $errors = [Collections.Generic.List[string]]::new()
    $result = [ordered]@{ phase=$Phase; complete=$false; passed=0; failed=0; assertions=0; cases=@(); processExitCode=$ExitCode; runnerErrors=@() }
    if ($RuntimeError) { $errors.Add($RuntimeError) }
    if ($null -ne $ExitCode -and $ExitCode -ne 0) { $errors.Add("$Phase 进程退出码：$ExitCode") }
    try {
        if (!(Test-Path -LiteralPath $ReportPath -PathType Leaf)) { throw '未生成阶段结果（启动失败或崩溃）' }
        $report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json -ErrorAction Stop
        if ($null -eq $report -or $report -isnot [pscustomobject]) { throw '阶段报告必须是 JSON 对象' }
        foreach ($field in @('phase','complete','passed','failed','assertions','cases')) {
            if ($null -eq $report.PSObject.Properties[$field]) { throw "阶段报告缺失字段：$field" }
        }
        if ($report.phase -cne $Phase) { throw "阶段名称不匹配：$($report.phase)" }
        if ($report.complete -isnot [bool] -or $report.cases -isnot [array]) { throw 'complete/cases 类型错误' }
        foreach ($field in @('passed','failed','assertions')) {
            if ($report.$field -isnot [long] -and $report.$field -isnot [int]) { throw "$field 必须为非负整数" }
            if ($report.$field -lt 0) { throw "$field 必须为非负整数" }
        }
        foreach ($case in $report.cases) {
            if ($null -eq $case -or $case.status -cnotin @('PASS','FAIL') -or !$case.group -or !$case.name) { throw '阶段报告含无效用例' }
        }
        $passed = @($report.cases | Where-Object status -CEQ 'PASS').Count
        $failed = @($report.cases | Where-Object status -CEQ 'FAIL').Count
        if ($report.passed -ne $passed -or $report.failed -ne $failed) { throw '阶段汇总与用例数量不一致' }
        if ($report.complete -and ($report.cases.Count -eq 0 -or $report.assertions -eq 0)) { throw '完整阶段必须至少包含一个用例和断言' }
        foreach ($field in @('complete','passed','failed','assertions','cases')) { $result[$field] = $report.$field }
    } catch {
        $errors.Add("$Phase 报告无效：$($_.Exception.Message)")
    }
    if (!$result.complete) { $errors.Add("$Phase 阶段未完整执行") }
    $result.runnerErrors = @($errors.ToArray())
    [pscustomobject]$result
}

function Write-BackendJUnit {
    param([Collections.IDictionary]$Metadata, [string]$Path, [switch]$SkipRestart)
    $xmlSettings = [Xml.XmlWriterSettings]::new()
    $xmlSettings.Indent = $true
    $xmlSettings.Encoding = [Text.UTF8Encoding]::new($false)
    $xml = [Xml.XmlWriter]::Create($Path, $xmlSettings)
    try {
        $xml.WriteStartDocument()
        $xml.WriteStartElement('testsuites')
        foreach ($phase in $Metadata.phases) {
            $hasError = @($phase.runnerErrors).Count -gt 0
            $xml.WriteStartElement('testsuite')
            $xml.WriteAttributeString('name', [string]$phase.phase)
            $xml.WriteAttributeString('tests', [string](@($phase.cases).Count + [int]$hasError))
            $xml.WriteAttributeString('failures', [string]$phase.failed)
            $xml.WriteAttributeString('errors', [string][int]$hasError)
            $xml.WriteAttributeString('skipped', '0')
            foreach ($case in $phase.cases) {
                $xml.WriteStartElement('testcase')
                $xml.WriteAttributeString('classname', [string]$case.group)
                $xml.WriteAttributeString('name', [string]$case.name)
                if ($case.status -ne 'PASS') { $xml.WriteElementString('failure', [string]$case.error) }
                $xml.WriteEndElement()
            }
            if ($hasError) {
                $xml.WriteStartElement('testcase')
                $xml.WriteAttributeString('classname', 'runner')
                $xml.WriteAttributeString('name', 'phase-completion')
                $xml.WriteElementString('error', ($phase.runnerErrors -join "`n"))
                $xml.WriteEndElement()
            }
            $xml.WriteEndElement()
        }
        if ($Metadata.Contains('error') -or $SkipRestart) {
            $hasError = $Metadata.Contains('error')
            $xml.WriteStartElement('testsuite')
            $xml.WriteAttributeString('name', 'runner')
            $xml.WriteAttributeString('tests', '1')
            $xml.WriteAttributeString('failures', '0')
            $xml.WriteAttributeString('errors', [string][int]$hasError)
            $xml.WriteAttributeString('skipped', [string][int](!$hasError))
            $xml.WriteStartElement('testcase')
            $xml.WriteAttributeString('name', 'complete-run')
            if ($hasError) { $xml.WriteElementString('error', [string]$Metadata.error) }
            else { $xml.WriteElementString('skipped', 'Restart phase not run') }
            $xml.WriteEndElement()
            $xml.WriteEndElement()
        }
        $xml.WriteEndElement()
        $xml.WriteEndDocument()
    } finally { $xml.Dispose() }
}
