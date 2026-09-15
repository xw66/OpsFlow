param(
    [ValidateSet('DisableAi', 'RestoreAi')] [string]$Mode,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
$app = 'opsflow-deploy-app-1'
$deployment = Join-Path $root 'target/deployment'
$snapshot = Join-Path $deployment 'opsflow-deploy.snapshot.compose.json'
if (!(Test-Path -LiteralPath $snapshot)) { throw '缺少私有部署快照；先运行tests/snapshot-deploy-config.ps1' }
$jar = Join-Path $root 'target/OpsFlow-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $jar -PathType Leaf)) { throw '缺少已验证的应用JAR' }
function Invoke-Docker { param([Parameter(ValueFromRemainingArguments = $true)] [string[]]$Arguments)
    $result = & docker @Arguments
    if ($LASTEXITCODE) { throw "Docker命令失败：$($Arguments -join ' ')" }
    return $result
}
if ((Invoke-Docker inspect $app --format '{{index .Config.Labels "com.docker.compose.project"}}') -ne 'opsflow-deploy') { throw '目标不是opsflow-deploy应用容器' }

$files = @('--project-directory', $root, '-f', $snapshot)
if ($Mode -eq 'DisableAi') { $files += @('-f', 'tests/deploy-ai-disabled.compose.yaml') }
$compose = @('-p', 'opsflow-deploy') + $files
Invoke-Docker -Arguments (@('compose') + $compose + @('config', '--quiet')) | Out-Null
$resolved = Invoke-Docker -Arguments (@('compose') + $compose + @('config', '--format', 'json')) | ConvertFrom-Json
$jarMount = $resolved.services.app.volumes | Where-Object { $_.type -eq 'bind' -and $_.target -eq '/app/app.jar' } | Select-Object -First 1
if ($null -eq $jarMount -or !$jarMount.read_only -or !(Test-Path -LiteralPath $jarMount.source -PathType Leaf) -or (Resolve-Path -LiteralPath $jarMount.source).Path -ne (Resolve-Path -LiteralPath $jar).Path) { throw 'Compose未解析到当前已验证JAR的只读挂载' }
Write-Output "已解析JAR挂载：$($jarMount.source) -> $($jarMount.target)"
$counts = Invoke-Docker exec opsflow-deploy-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -uopsflow -N -e "SELECT status,COUNT(*) FROM opsflow.ticket_ai_analysis GROUP BY status"'
$categories = Invoke-Docker exec opsflow-deploy-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -uopsflow -N -e "SELECT COUNT(*) FROM opsflow.ticket_category c JOIN opsflow.support_group g ON g.id=c.group_id WHERE c.enabled=TRUE AND g.enabled=TRUE"'
if ($Mode -eq 'DisableAi') {
    $counts | ForEach-Object { Write-Output "AI预检计数：$_" }
    Write-Output "AI可用分类计数：$categories"
}
if ($Mode -eq 'RestoreAi') {
    $pending = Invoke-Docker exec opsflow-deploy-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -uopsflow -N -e "SELECT COUNT(*) FROM opsflow.ticket_ai_analysis WHERE status IN (\"WAITING\",\"PENDING\",\"PROCESSING\")"'
    if ([int]$pending -ne 0) { throw "仍有$pending条待处理AI任务，拒绝恢复外部模型" }
}
if ($WhatIf) { Write-Output "将以$($Mode)重建app；仅app替换，依赖容器和命名卷不变"; return }
Invoke-Docker -Arguments (@('compose') + $compose + @('up', '-d', '--no-deps', '--no-build', '--force-recreate', 'app')) | Out-Null
for ($i = 0; $i -lt 60; $i++) {
    $binding = Invoke-Docker port $app 8080/tcp
    if ($binding -match ':(\d+)$') {
        try {
            if ((Invoke-RestMethod "http://127.0.0.1:$($Matches[1])/actuator/health").status -eq 'UP') { Write-Output '应用已就绪'; return }
        } catch { }
    }
    Start-Sleep -Seconds 1
}
throw '应用未在60秒内就绪；请使用同一部署环境文件执行RestoreAi恢复原AI配置'
