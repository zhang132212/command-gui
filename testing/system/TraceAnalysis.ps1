# Streaming, read-only analysis of test-only mod payload/event logs. Never interprets payload text as instructions.
function Read-SystemTraces {
    param([string]$OutputRoot,[string]$TraceRoot,[switch]$Required,[ValidateRange(10,2000)][int]$MaxTimelineEvents=300)
    if (!$TraceRoot) { $TraceRoot=Join-Path $OutputRoot 'dedicated/artifacts' }
    $issues=[Collections.Generic.List[object]]::new()
    $failureEvents=[Collections.Generic.List[object]]::new()
    $candidates=[Collections.Generic.List[object]]::new()
    $roles=[Collections.Generic.List[object]]::new()
    $packetCounts=@{};$eventCounts=@{};$failureCount=0;$keyEventCount=0;$totalLines=0;$parsedLines=0
    foreach ($definition in @(@{directory='server';role='server'},@{directory='client-ActorA';role='ActorA'},@{directory='client-ActorB';role='ActorB'})) {
        $path=Join-Path $TraceRoot "$($definition.directory)/events.jsonl"
        $relative=[IO.Path]::GetRelativePath($OutputRoot,$path).Replace('\','/')
        $url=($relative.Split('/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
        if (!(Test-Path -LiteralPath $path -PathType Leaf)) {
            if ($Required) { $issues.Add([pscustomobject]@{role=$definition.role;path=$relative;url=$url;line=0;code='missing-trace';message='Role events.jsonl is missing'}) }
            continue
        }
        $roleInfo=[ordered]@{role=$definition.role;path=$relative;url=$url;lines=0;parsed=0;packets=0;firstUtc=$null;lastUtc=$null;lastSequence=0;failures=0;issues=0}
        $stream=$null;$reader=$null;$expectedSequence=1L;$previousElapsed=-1L;$roleCandidates=0
        $issuesBefore=$issues.Count
        try {
            $stream=[IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
            if ($stream.Length -eq 0) { $issues.Add([pscustomobject]@{role=$definition.role;path=$relative;url=$url;line=0;code='empty-trace';message='Trace is empty'}) }
            else {
                [void]$stream.Seek(-1,[IO.SeekOrigin]::End)
                if ($stream.ReadByte() -ne 10) { $issues.Add([pscustomobject]@{role=$definition.role;path=$relative;url=$url;line=-1;code='unterminated-tail';message='Last line has no newline; possible interrupted/truncated final write'}) }
                [void]$stream.Seek(0,[IO.SeekOrigin]::Begin)
            }
            $reader=[IO.StreamReader]::new($stream,[Text.UTF8Encoding]::new($false,$true),$true)
            while (($line=$reader.ReadLine()) -ne $null) {
                $roleInfo.lines++;$totalLines++
                try {
                    if ([string]::IsNullOrWhiteSpace($line)) { throw 'Empty JSONL line' }
                    $row=$line | ConvertFrom-Json -Depth 100 -ErrorAction Stop
                    if ($null -eq $row -or $row -isnot [pscustomobject]) { throw 'Event must be a JSON object' }
                    $roleInfo.parsed++;$parsedLines++
                    $sequence=Get-ReportField $row 'seq'
                    Assert-ReportInteger $sequence 'seq' 1
                    if ($sequence -ne $expectedSequence) { $issues.Add([pscustomobject]@{role=$definition.role;path=$relative;url=$url;line=$roleInfo.lines;code='sequence-gap';message="Expected seq=$expectedSequence, observed=$sequence"}) }
                    $expectedSequence=$sequence+1;$roleInfo.lastSequence=$sequence
                    $elapsed=Get-ReportField $row 'elapsedNanos'
                    Assert-ReportInteger $elapsed 'elapsedNanos'
                    if ($elapsed -lt $previousElapsed) { $issues.Add([pscustomobject]@{role=$definition.role;path=$relative;url=$url;line=$roleInfo.lines;code='nonmonotonic-time';message="elapsedNanos decreased: $previousElapsed -> $elapsed"}) }
                    $previousElapsed=$elapsed
                    if ((Get-ReportField $row 'role') -cne $definition.role) { throw "Role mismatch: expected $($definition.role)" }
                    $utc=Get-ReportField $row 'utc'
                    $date=[DateTimeOffset]::MinValue
                    # PowerShell 7.5+ may automatically parse ISO JSON strings as DateTime.
                    if ($utc -is [DateTime] -or $utc -is [DateTimeOffset]) { $utc=([DateTimeOffset]$utc).ToUniversalTime().ToString('o') }
                    if ($utc -isnot [string] -or ![DateTimeOffset]::TryParse($utc,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::RoundtripKind,[ref]$date)) { throw 'Invalid event UTC timestamp' }
                    if ($null -eq $roleInfo.firstUtc) { $roleInfo.firstUtc=$utc };$roleInfo.lastUtc=$utc
                    $event=Get-ReportField $row 'event'
                    if ($event -isnot [string] -or [string]::IsNullOrWhiteSpace($event)) { throw 'Missing event name' }
                    $eventKey="$($definition.role)|$event"
                    if (!$eventCounts.ContainsKey($eventKey)) { $eventCounts[$eventKey]=[ordered]@{role=$definition.role;event=$event;count=0} }
                    $eventCounts[$eventKey].count++
                    $data=Get-ReportField $row 'data'
                    if ($event -ceq 'packet') {
                        $direction=Get-ReportField $data 'direction';$type=Get-ReportField $data 'type';$memory=Get-ReportField $data 'memoryConnection'
                        if ($direction -cnotin @('send','receive') -or $type -isnot [string] -or [string]::IsNullOrWhiteSpace($type) -or $memory -isnot [bool]) { throw 'Invalid packet direction/type/memoryConnection' }
                        $key="$($definition.role)|$direction|$type"
                        if (!$packetCounts.ContainsKey($key)) { $packetCounts[$key]=[ordered]@{role=$definition.role;direction=$direction;payload=$type;count=0;tcp=0;memory=0} }
                        $packetCounts[$key].count++;$roleInfo.packets++
                        if ($memory) { $packetCounts[$key].memory++ } else { $packetCounts[$key].tcp++ }
                    }
                    $isFailure=$event -cin @('stage.fail','server.failure') -or (($event -cin @('assertion','server.assert','server.finished')) -and (Get-ReportField $data 'passed') -ceq $false)
                    $isKey=$isFailure -or $event -cin @('process.start','server.ready','server.join','server.disconnect','stage.start','stage.pass','client.command','scheduler.execute','transport.connect','transport.disconnect-event','server.finished')
                    if ($isKey) {
                        $keyEventCount++
                        $summary=if ($data -is [string]) { $data } else { ConvertTo-Json -InputObject $data -Depth 25 -Compress }
                        if ($summary.Length -gt 2048) { $summary=$summary.Substring(0,2048)+' … [full event in original log]' }
                        $entry=[pscustomobject]@{role=$definition.role;seq=$sequence;utc=$utc;sortUtc=$date.UtcTicks;event=$event;path=$relative;url=$url;line=$roleInfo.lines;summary=$summary;failed=$isFailure}
                        if ($isFailure) { $failureCount++;$roleInfo.failures++;if ($failureEvents.Count -lt 100) { $failureEvents.Add($entry) } }
                        elseif ($roleCandidates -lt $MaxTimelineEvents) { $candidates.Add($entry);$roleCandidates++ }
                    }
                } catch { $issues.Add([pscustomobject]@{role=$definition.role;path=$relative;url=$url;line=$roleInfo.lines;code='invalid-event';message=$_.Exception.Message}) }
            }
        } catch { $issues.Add([pscustomobject]@{role=$definition.role;path=$relative;url=$url;line=$roleInfo.lines+1;code='trace-read-error';message=$_.Exception.Message}) }
        finally { if ($null -ne $reader) { $reader.Dispose() } elseif ($null -ne $stream) { $stream.Dispose() } }
        $roleInfo.issues=$issues.Count-$issuesBefore
        $roles.Add([pscustomobject]$roleInfo)
    }
    $ordered=@($candidates.ToArray() | Sort-Object sortUtc,role,seq)
    $available=[Math]::Max(0,$MaxTimelineEvents-$failureEvents.Count)
    if ($ordered.Count -gt $available) {
        $first=[int][Math]::Floor($available/2);$last=$available-$first
        $ordered=@(@($ordered | Select-Object -First $first)+@($ordered | Select-Object -Last $last))
    }
    $timeline=@(@($ordered)+@($failureEvents.ToArray()) | Sort-Object sortUtc,role,seq)
    [pscustomobject]@{
        status=$(if ($issues.Count -gt 0) {'ERROR'} elseif ($failureCount -gt 0) {'FAIL'} elseif ($roles.Count -eq 0) {'NOT_RUN'} else {'PASS'})
        files=$roles.Count;lines=$totalLines;parsedLines=$parsedLines;issues=@($issues.ToArray());roles=@($roles.ToArray())
        packetStats=@($packetCounts.Values | Sort-Object role,direction,payload);eventStats=@($eventCounts.Values | Sort-Object role,event)
        failureEventCount=$failureCount;failureEvents=@($failureEvents.ToArray());keyEventCount=$keyEventCount;timeline=$timeline;timelineOmitted=[Math]::Max(0,$keyEventCount-$timeline.Count)
        scope='Complete command-gui custom payloads and test events; not a capture of every vanilla Minecraft TCP packet. Original JSONL retains full payloads.'
    }
}
