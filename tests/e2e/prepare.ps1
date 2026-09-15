param([switch]$Restart)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $taskRoot
$taskOutput = Join-Path $taskRoot 'target/acceptance'
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
$taskCredentialsFile = Join-Path $taskOutput 'credentials.json'
if (!(Test-Path -LiteralPath $taskCredentialsFile)) {
    @{ password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)); jwt = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48)) } |
        ConvertTo-Json | Set-Content -LiteralPath $taskCredentialsFile -Encoding utf8
}
$taskCredentials = Get-Content -Raw -LiteralPath $taskCredentialsFile | ConvertFrom-Json
$taskAiEnabled = if ($env:OPSFLOW_ACCEPTANCE_AI_ENABLED -eq 'true') { 'true' } else { 'false' }
$taskEnvFile = Join-Path $taskOutput 'dependencies.env'
@"
MYSQL_PASSWORD=$($taskCredentials.password)
MYSQL_ROOT_PASSWORD=$($taskCredentials.password)
MYSQL_PORT=3318
REDIS_PORT=6391
KAFKA_PORT=9106
"@ | Set-Content -LiteralPath $taskEnvFile -Encoding utf8
$env:JWT_SECRET = $taskCredentials.jwt
docker compose -p opsflow-acceptance --env-file $taskEnvFile up -d --wait --wait-timeout 120 mysql redis kafka
if ($LASTEXITCODE) { throw 'E2E隔离依赖启动失败' }
$taskPidFile = Join-Path $taskOutput 'app.pid'
if (Test-Path -LiteralPath $taskPidFile) {
    $taskPreviousId = [int](Get-Content -LiteralPath $taskPidFile)
    $taskPrevious = Get-CimInstance Win32_Process -Filter "ProcessId=$taskPreviousId"
    if ($taskPrevious) {
        if ($taskPrevious.Name -ne 'java.exe' -or $taskPrevious.CommandLine -notlike '*target/acceptance/application.properties*') { throw 'PID不属于验收应用，拒绝操作' }
        if (!$Restart) { throw 'E2E应用仍在运行；复用时直接运行测试，更新JAR后用-Restart重启' }
        Stop-Process -Id $taskPreviousId
    }
}
@"
server.port=8182
spring.datasource.url=jdbc:mysql://localhost:3318/opsflow?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
spring.datasource.password=$($taskCredentials.password)
spring.data.redis.port=6391
spring.kafka.bootstrap-servers=localhost:9106
opsflow.jwt.secret=$($taskCredentials.jwt)
opsflow.bootstrap.enabled=true
opsflow.bootstrap.username=e2e_admin
opsflow.bootstrap.password=$($taskCredentials.password)
opsflow.assignment.enabled=false
opsflow.rate-limit.enabled=false
opsflow.sla.enabled=true
opsflow.ai.enabled=$taskAiEnabled
opsflow.ai.worker-enabled=true
opsflow.attachments.directory=target/acceptance/attachments
logging.file.name=target/acceptance/application.log
"@ | Set-Content -LiteralPath (Join-Path $taskOutput 'application.properties') -Encoding utf8
if (!$env:JAVA_HOME) { throw '请先将当前PowerShell进程JAVA_HOME设为项目JDK25' }
$taskJar = Join-Path $taskRoot 'target/OpsFlow-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $taskJar)) { throw '请先执行mvnw verify生成JAR' }
$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$taskRuntimeJar = Join-Path $taskOutput "app-$taskStamp.jar"
# Windows运行中的JAR会被占用，使用独立副本保留Maven构建能力。
Copy-Item -LiteralPath $taskJar -Destination $taskRuntimeJar
$taskProcess = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList @('-Xms256m','-Xmx768m','-jar',('"'+$taskRuntimeJar+'"'),'--spring.config.import=optional:file:target/acceptance/application.properties') -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskOutput "app-$taskStamp.out.log") -RedirectStandardError (Join-Path $taskOutput "app-$taskStamp.err.log")
$taskProcess.Id | Set-Content -LiteralPath $taskPidFile
$taskReady = $false
for ($taskAttempt = 0; $taskAttempt -lt 45; $taskAttempt++) {
    if ($taskProcess.HasExited) { throw 'E2E应用已退出，请检查target/e2e日志' }
    try { $taskReady = (Invoke-RestMethod 'http://127.0.0.1:8182/actuator/health').status -eq 'UP' } catch { }
    if ($taskReady) { break }
    Start-Sleep -Seconds 1
}
if (!$taskReady) { throw '应用尚未就绪，保留当前进程排查，不自动重启' }
@{ startedAt = (Get-Date).ToUniversalTime().ToString('o'); jarSha256 = (Get-FileHash -LiteralPath $taskJar).Hash; port = 8182; project = 'opsflow-acceptance'; automaticAssignment = $false; rateLimit = $false; externalAi = $false } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOutput 'runtime.json') -Encoding utf8
Write-Output "验收应用已就绪：http://127.0.0.1:8182，PID=$($taskProcess.Id)"
