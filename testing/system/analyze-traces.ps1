#Requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$TraceRoot,[string]$OutputFile,[ValidateRange(10,2000)][int]$MaxTimelineEvents=300)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ReportLibrary.ps1')
$TraceRoot=[IO.Path]::GetFullPath($TraceRoot)
if (!$OutputFile) { $OutputFile=Join-Path $TraceRoot 'trace-analysis.json' }
$result=Read-SystemTraces -OutputRoot $TraceRoot -TraceRoot $TraceRoot -Required -MaxTimelineEvents $MaxTimelineEvents
$result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $OutputFile -Encoding utf8
Write-Host "[trace] $($result.status): files=$($result.files), lines=$($result.lines), parsed=$($result.parsedLines), issues=$($result.issues.Count), failures=$($result.failureEventCount)"
foreach ($issue in $result.issues) { Write-Host "[trace] $($issue.role) line=$($issue.line) $($issue.code): $($issue.message)" }
Write-Host "[trace] Analysis: $OutputFile"
if ($result.status -ne 'PASS') { exit 1 };exit 0
