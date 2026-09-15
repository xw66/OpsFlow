param([string]$BaseUrl = 'http://127.0.0.1:8082', [string]$AdminUsername, [string]$AdminPassword, [switch]$WhatIf)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$snapshot = Join-Path $root 'target/deployment/opsflow-deploy.snapshot.compose.json'
if (!(Test-Path -LiteralPath $snapshot)) { throw '缺少私有部署快照' }
if ($WhatIf) { Write-Output '将创建一张隔离工单，验证自动分类、摘要和回复各一次；不输出模型原文或凭据'; return }
$config = Get-Content -Raw -LiteralPath $snapshot | ConvertFrom-Json
function Value($name) { return [string]($config.services.app.environment.$name -replace '\$\$', '$') }
$adminName = if ($AdminUsername) { $AdminUsername } else { Value 'BOOTSTRAP_ADMIN_USERNAME' }
$adminPassword = if ($AdminPassword) { $AdminPassword } else { Value 'BOOTSTRAP_ADMIN_PASSWORD' }
if ([string]::IsNullOrWhiteSpace($adminName) -or [string]::IsNullOrWhiteSpace($adminPassword)) { throw '私有快照没有可用的管理员初始化账号' }

function Api($method, $path, $body = $null, $token = $null) {
    $headers = @{ 'Idempotency-Key' = [Guid]::NewGuid().ToString('N') }
    if ($token) { $headers.Authorization = "Bearer $token" }
    $args = @{ Method = $method; Uri = "$BaseUrl$path"; Headers = $headers; ContentType = 'application/json; charset=utf-8' }
    if ($null -ne $body) { $args.Body = $body | ConvertTo-Json -Depth 8 -Compress }
    try { return (Invoke-RestMethod @args).data }
    catch { throw "$method $path 失败：$($_.Exception.Message)" }
}
function Token($username, $password) { return (Api 'POST' '/api/auth/login' @{ username = $username; password = $password }).accessToken }
function Wait-Analysis($ticketId, $kind, $token) {
    for ($i = 0; $i -lt 90; $i++) {
        $analysis = @(Api 'GET' "/api/tickets/$ticketId/ai-analyses?offset=0&limit=20" $null $token | Where-Object { $_.kind -eq $kind } | Select-Object -First 1)
        if ($analysis.Count -and $analysis[0].status -eq 'SUCCEEDED' -and ![string]::IsNullOrWhiteSpace($analysis[0].resultJson)) { return $analysis[0] }
        if ($analysis.Count -and $analysis[0].status -in @('FAILED', 'DISABLED')) { throw "$kind 未成功完成" }
        Start-Sleep -Seconds 2
    }
    throw "$kind 超时"
}

$admin = Token $adminName $adminPassword
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$owner = Api 'POST' '/api/auth/register' @{ username = "aiowner_$suffix"; password = $password; displayName = 'AI隔离用户' }
$leader = Api 'POST' '/api/auth/register' @{ username = "aileader_$suffix"; password = $password; displayName = 'AI隔离组长' }
Api 'PUT' "/api/admin/users/$($leader.id)/roles" @{ roles = @('USER', 'LEADER'); reason = 'AI隔离验证' } $admin | Out-Null
$leaderToken = Token $leader.username $password
$group = Api 'POST' '/api/admin/support-groups' @{ name = "AI隔离组$suffix"; leaderId = $leader.id; enabled = $true; version = 0 } $admin
$category = Api 'POST' '/api/admin/ticket-categories' @{ code = "AI_$suffix".ToUpperInvariant(); name = 'AI隔离分类'; groupId = $group.id; enabled = $true; version = 0 } $admin
Api 'POST' '/api/admin/sla-policies' @{ categoryId = $category.id; priority = 'HIGH'; responseMinutes = 30; resolveMinutes = 240; autoEscalate = $true; enabled = $true; version = 0 } $admin | Out-Null
$ticket = (Api 'POST' '/api/tickets' @{ title = 'AI隔离验证'; description = '仅验证三种AI链路。'; categoryId = $category.id; priority = 'HIGH'; tags = @() } (Token $owner.username $password)).ticket
$classification = Wait-Analysis $ticket.id 'CLASSIFICATION' $leaderToken
foreach ($kind in @('SUMMARY', 'REPLY')) {
    Api 'POST' "/api/tickets/$($ticket.id)/ai-analyses" @{ kind = $kind; version = $ticket.version } $leaderToken | Out-Null
    $analysis = Wait-Analysis $ticket.id $kind $leaderToken
    $calls = @(Api 'GET' "/api/tickets/$($ticket.id)/ai-analyses/$($analysis.id)/calls" $null $leaderToken)
    if ($calls.Count -eq 0 -or $calls[0].outcome -ne 'SUCCEEDED' -or [string]::IsNullOrWhiteSpace($calls[0].model)) { throw "$kind 调用记录无效" }
}
$forbidden = Invoke-WebRequest -Method Get -Uri "$BaseUrl/api/tickets/$($ticket.id)/ai-analyses/$($classification.id)/calls" -Headers @{ Authorization = "Bearer $(Token $owner.username $password)" } -SkipHttpErrorCheck
if ($forbidden.StatusCode -ne 403) { throw '普通用户不应读取AI调用记录' }
Write-Output '三种AI链路均成功，调用记录与权限检查通过'
