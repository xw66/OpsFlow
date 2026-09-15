param([switch]$WhatIf)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$app = 'opsflow-deploy-app-1'
$jar = Join-Path $root 'target/OpsFlow-0.0.1-SNAPSHOT.jar'
function Invoke-Docker { param([Parameter(ValueFromRemainingArguments = $true)] [string[]]$Arguments)
    $result = & docker @Arguments
    if ($LASTEXITCODE) { throw "Docker命令失败：$($Arguments -join ' ')" }
    return $result
}
if (!(Test-Path -LiteralPath $jar)) { throw '缺少已验证的应用JAR' }
if ((Invoke-Docker inspect $app --format '{{index .Config.Labels "com.docker.compose.project"}}') -ne 'opsflow-deploy') { throw '目标不是opsflow-deploy应用容器' }
if ((Invoke-Docker inspect $app --format '{{json .Config.Entrypoint}}') -ne '["java","-jar","/app/app.jar"]') { throw '应用入口与预期不符，拒绝覆盖' }
if ($WhatIf) { Write-Output "将保留$($app)的环境、端口和卷，仅替换/app/app.jar：$((Get-FileHash $jar).Hash)"; return }

$backup = Join-Path $root ('target/deployment-prep-backup-20260915/app-before-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.jar')
New-Item -ItemType Directory -Force -Path (Split-Path $backup) | Out-Null
Invoke-Docker cp "${app}:/app/app.jar" $backup
Invoke-Docker stop $app | Out-Null
try {
    Invoke-Docker cp $jar "${app}:/app/app.jar"
    Invoke-Docker start $app | Out-Null
} catch {
    Invoke-Docker cp $backup "${app}:/app/app.jar"
    Invoke-Docker start $app | Out-Null
    throw
}
for ($i = 0; $i -lt 60; $i++) {
    $binding = Invoke-Docker port $app 8080/tcp
    if ($binding -match ':(\d+)$') {
        try {
            if ((Invoke-RestMethod "http://127.0.0.1:$($Matches[1])/actuator/health").status -eq 'UP') { Write-Output '应用已就绪'; return }
        } catch { }
    }
    Start-Sleep -Seconds 1
}
throw '应用未在60秒内就绪；已保留替换前JAR备份，未触碰依赖容器或数据卷'
