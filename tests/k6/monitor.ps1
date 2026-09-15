param([string]$Label = 'recovery', [int]$Samples = 10, [int]$IntervalSeconds = 2, [string]$Project = 'opsflow-bench')
$ErrorActionPreference = 'Stop'
if ($Label -notmatch '^[a-zA-Z0-9_-]{1,30}$' -or $Samples -lt 1 -or $Samples -gt 300) { throw '监测参数无效' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $root 'target/benchmark'
for ($i = 0; $i -lt $Samples; $i++) {
    $started = (Get-Date).ToUniversalTime()
    $raw = docker exec "$Project-kafka-1" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:19092 --describe --group opsflow-notifications 2>&1
    $cliExit = $LASTEXITCODE
    $raw | Add-Content (Join-Path $output "$Label-kafka.txt")
    $partitions = @($raw | Where-Object { "$_" -match '^opsflow-notifications\s+opsflow.ticket-events\s+' })
    $lag = $null
    if ($cliExit -eq 0 -and $partitions.Count -eq 3) {
        $values = @($partitions | ForEach-Object { ("$_" -split '\s+')[5] })
        if (@($values | Where-Object { $_ -notmatch '^\d+$' }).Count -eq 0) { $lag = ($values | ForEach-Object { [long]$_ } | Measure-Object -Sum).Sum }
    }
    $sql = "SELECT JSON_OBJECT('pendingOutbox',(SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT'),'consumed',(SELECT COUNT(*) FROM consumed_event WHERE consumer_name='opsflow-notifications'),'notifications',(SELECT COUNT(*) FROM notification),'slaEvents',(SELECT COUNT(*) FROM sla_event),'openFailures',(SELECT COUNT(*) FROM consumer_failure WHERE status='OPEN'));"
    $facts = (& (Join-Path $PSScriptRoot 'sql.ps1') -Project $Project -Sql $sql) | ConvertFrom-Json
    $row = @{ startedAt = $started.ToString('o'); finishedAt = (Get-Date).ToUniversalTime().ToString('o'); kafkaLag = $lag; cliExit = $cliExit; facts = $facts }
    $row | ConvertTo-Json -Depth 5 -Compress | Add-Content (Join-Path $output "$Label.jsonl")
    Write-Output "样本$i KafkaLag=$lag Outbox=$($facts.pendingOutbox) 已消费=$($facts.consumed)"
    if ($i + 1 -lt $Samples) { Start-Sleep -Seconds $IntervalSeconds }
}
