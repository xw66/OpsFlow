param([string]$BaseUrl = 'http://localhost:8081')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $root 'target/benchmark'
$credentials = Get-Content (Join-Path $output 'credentials.json') -Raw | ConvertFrom-Json
function Api($method, $path, $body, $token = '', $key = '') {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    if ($key) { $headers.'Idempotency-Key' = $key }
    $arguments = @{ Method = $method; Uri = "$BaseUrl$path"; Headers = $headers; ContentType = 'application/json; charset=utf-8' }
    if ($null -ne $body) { $arguments.Body = $body | ConvertTo-Json -Depth 10 -Compress }
    (Invoke-RestMethod @arguments).data
}
$adminToken = (Api Post '/api/auth/login' @{ username = 'bench_admin'; password = $credentials.password }).accessToken
$suffix = (Get-Date -Format 'MMddHHmmss')
$users = @{}
foreach ($role in @('owner','agent','leader')) {
    $username = "bench_${role}_$suffix"
    $user = Api Post '/api/auth/register' @{ username = $username; password = $credentials.password; displayName = "压测$role" }
    if ($role -ne 'owner') { Api Put "/api/admin/users/$($user.id)/roles" @{ roles = @('USER', $role.ToUpperInvariant()); reason = '独立压测初始化' } $adminToken | Out-Null }
    $users[$role] = @{ id = $user.id; username = $username; token = (Api Post '/api/auth/login' @{ username = $username; password = $credentials.password }).accessToken }
}
$group = Api Post '/api/admin/support-groups' @{ name = "压测组$suffix"; leaderId = $users.leader.id; enabled = $true; version = 0 } $adminToken
Api Post '/api/admin/support-agents' @{ userId = $users.agent.id; groupId = $group.id; enabled = $true; version = 0 } $adminToken | Out-Null
Api Put '/api/support/agents/me/online' @{ online = $true; version = 0 } $users.agent.token | Out-Null
$category = Api Post '/api/admin/ticket-categories' @{ code = "BENCH_$suffix"; name = '压测分类'; groupId = $group.id; enabled = $true; version = 0 } $adminToken
foreach ($priority in @('HIGH','URGENT')) {
    $minutes = if ($priority -eq 'URGENT') { 1 } else { 1440 }
    Api Post '/api/admin/sla-policies' @{ categoryId = $category.id; priority = $priority; responseMinutes = $minutes; resolveMinutes = $minutes; autoEscalate = $true; enabled = $true; version = 0 } $adminToken | Out-Null
}
$body = @{ title = "完整流程$suffix"; description = '真实HTTP流程验证'; categoryId = $category.id; priority = 'HIGH'; tags = @('benchmark') }
$ticket = (Api Post '/api/tickets' $body $users.owner.token "flow_$suffix").ticket
$flowId = $ticket.id
$ticket = Api Post "/api/tickets/$flowId/assign" @{ assigneeId = $users.agent.id; version = $ticket.version; reason = '演示分配' } $adminToken
foreach ($action in @('accept','suspend','resume','resolve','close')) {
    $ticket = Api Post "/api/tickets/$flowId/$action" @{ version = $ticket.version; reason = "流程$action" } $users.agent.token
}
if ($ticket.status -ne 'CLOSED') { throw '完整流程未关闭' }
$history = @(Api Get "/api/tickets/$flowId/history" $null $users.owner.token)
if ($history.Count -ne 7) { throw '状态历史条数不符' }
$body.title = "竞争接单$suffix"
$race = (Api Post '/api/tickets' $body $users.owner.token "race_$suffix").ticket
$race = Api Post "/api/tickets/$($race.id)/assign" @{ assigneeId = $users.agent.id; version = $race.version; reason = '并发接单准备' } $adminToken
@{ baseUrl = "http://host.docker.internal:$(([uri]$BaseUrl).Port)"; adminToken = $adminToken; users = $users; categoryId = $category.id; groupId = $group.id; raceId = $race.id; raceVersion = $race.version; detailId = $flowId; runId = $suffix } | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $output 'fixture.json') -Encoding utf8
@{ completedAt = (Get-Date).ToUniversalTime().ToString('o'); ticketId = $flowId; status = $ticket.status; version = $ticket.version; history = $history } | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $output 'business-flow.json') -Encoding utf8
Write-Output "HTTP完整流程通过：工单$flowId，7条历史；压测组$($group.id)，并发工单$($race.id)"
