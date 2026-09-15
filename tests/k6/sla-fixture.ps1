param([int]$Count = 30, [string]$BaseUrl = 'http://localhost:8081')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $root 'target/benchmark'
$fixture = Get-Content (Join-Path $output 'fixture.json') -Raw | ConvertFrom-Json
$records = @()
$run = Get-Date -Format 'MMddHHmmss'
for ($i = 0; $i -lt $Count; $i++) {
    $headers = @{ Authorization = "Bearer $($fixture.users.owner.token)"; 'Idempotency-Key' = "sla_${run}_$i" }
    $body = @{ title = "自然时间SLA-$run-$i"; description = '使用真实一分钟规则等待违约，不修改数据库deadline'; categoryId = $fixture.categoryId; priority = 'URGENT'; tags = @('benchmark') } | ConvertTo-Json
    $ticket = (Invoke-RestMethod "$BaseUrl/api/tickets" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body).data.ticket
    $records += @{ id = $ticket.id; createdAt = $ticket.createdAt; responseDeadline = $ticket.responseDeadline; resolveDeadline = $ticket.resolveDeadline }
}
$records | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output "sla-$run.json")
Write-Output "已创建$($Count)条一分钟SLA工单，run=$run"
