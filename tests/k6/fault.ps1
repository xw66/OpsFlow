param([ValidateSet('redis','kafka')][string]$Dependency, [string]$BaseUrl = 'http://localhost:8081', [string]$Project = 'opsflow-bench', [int]$KafkaWaitSeconds = 12)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $root 'target/benchmark'
$fixturePath = Join-Path $output 'fixture.json'
$fixture = Get-Content $fixturePath -Raw | ConvertFrom-Json
$credentials = Get-Content (Join-Path $output 'credentials.json') -Raw | ConvertFrom-Json
$loginBody = @{ username = $fixture.users.owner.username; password = $credentials.password } | ConvertTo-Json
$fixture.users.owner.token = (Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody).data.accessToken
$fixture | ConvertTo-Json -Depth 10 | Set-Content $fixturePath
$run = Get-Date -Format 'MMddHHmmss'
$key = "fault_${Dependency}_$run"
$headers = @{ Authorization = "Bearer $($fixture.users.owner.token)"; 'Idempotency-Key' = $key }
$body = @{ title = "依赖故障$Dependency-$run"; description = '验证已授权独立压测环境中的降级及恢复'; categoryId = $fixture.categoryId; priority = 'HIGH'; tags = @('benchmark') } | ConvertTo-Json
$evidence = @{ dependency = $Dependency; startedAt = (Get-Date).ToUniversalTime().ToString('o'); key = $key }
$container = "$Project-$Dependency-1"
try {
    docker stop $container | Out-Null
    if ($LASTEXITCODE) { throw '依赖停止失败' }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $created = Invoke-WebRequest "$BaseUrl/api/tickets" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body -SkipHttpErrorCheck
    $watch.Stop()
    $evidence.createStatus = [int]$created.StatusCode; $evidence.createElapsedMs = $watch.Elapsed.TotalMilliseconds
    if ($created.StatusCode -ne 201) { throw '依赖故障导致创建失败' }
    $ticketId = ($created.Content | ConvertFrom-Json).data.ticket.id
    $evidence.ticketId = $ticketId
    if ($Dependency -eq 'redis') {
        $again = Invoke-WebRequest "$BaseUrl/api/tickets" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body -SkipHttpErrorCheck
        $detail = Invoke-WebRequest "$BaseUrl/api/tickets/$ticketId" -Headers $headers -SkipHttpErrorCheck
        $login = Invoke-WebRequest "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody -SkipHttpErrorCheck
        $evidence.replayStatus = [int]$again.StatusCode; $evidence.detailStatus = [int]$detail.StatusCode; $evidence.loginStatus = [int]$login.StatusCode
        if ($again.StatusCode -ne 201 -or ($again.Content | ConvertFrom-Json).data.ticket.id -ne $ticketId -or $detail.StatusCode -ne 200 -or $login.StatusCode -ne 503) { throw 'Redis故障策略不符合预期，请确认启动时启用-RateLimit' }
    } else {
        Start-Sleep -Seconds $KafkaWaitSeconds
        $pending = & (Join-Path $PSScriptRoot 'sql.ps1') -Project $Project -Sql "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=$ticketId AND status<>'SENT'"
        $evidence.pendingDuringFault = [int]$pending
        if ([int]$pending -ne 1) { throw '故障期间未保留待发送事件' }
        $failures = & (Join-Path $PSScriptRoot 'sql.ps1') -Project $Project -Sql "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=$ticketId AND last_error IS NOT NULL AND attempts>0"
        $evidence.failedAttemptsObserved = [int]$failures
        if ([int]$failures -ne 1) { throw '未观察到投递失败记录' }
    }
} finally {
    docker start $container | Out-Null
    $evidence.dependencyRestartedAt = (Get-Date).ToUniversalTime().ToString('o')
    $evidence | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output "fault-$Dependency-$run.json")
}
if ($LASTEXITCODE) { throw '依赖重启失败' }
$recovered = $false
for ($i = 0; $i -lt 60; $i++) {
    $count = & (Join-Path $PSScriptRoot 'sql.ps1') -Project $Project -Sql "SELECT COUNT(*) FROM outbox_event o JOIN consumed_event c ON c.event_id=o.event_id AND c.consumer_name='opsflow-notifications' WHERE o.aggregate_id=$ticketId AND o.status='SENT'"
    if ([int]$count -eq 1) { $recovered = $true; break }
    Start-Sleep -Seconds 2
}
$evidence.recovered = $recovered
if ($Dependency -eq 'redis') {
    $loginAfter = Invoke-WebRequest "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody -SkipHttpErrorCheck
    $evidence.restoredLoginStatus = [int]$loginAfter.StatusCode
    if ($loginAfter.StatusCode -ne 200) { $recovered = $false; $evidence.recovered = $false }
}
$evidence.observedRecoveredAt = (Get-Date).ToUniversalTime().ToString('o')
$evidence | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output "fault-$Dependency-$run.json")
if (!$recovered) { throw '恢复窗口内未观察到事件完成消费' }
Write-Output "$Dependency 故障及恢复验证通过，工单$ticketId"
