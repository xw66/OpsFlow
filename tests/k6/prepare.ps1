param([switch]$Restart, [switch]$Sla, [switch]$RateLimit, [switch]$PauseNotifications, [int]$Port = 8081, [string]$Project = 'opsflow-bench')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $root
$output = Join-Path $root 'target/benchmark'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$credentialsPath = Join-Path $output 'credentials.json'
if (!(Test-Path $credentialsPath)) {
    $credentials = @{ password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)); jwt = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48)) }
    $credentials | ConvertTo-Json | Set-Content $credentialsPath -Encoding utf8
} else { $credentials = Get-Content $credentialsPath -Raw | ConvertFrom-Json }
$dependencyFile = Join-Path $output 'dependencies.env'
@"
MYSQL_PASSWORD=$($credentials.password)
MYSQL_ROOT_PASSWORD=$($credentials.password)
MYSQL_PORT=3307
REDIS_PORT=6380
KAFKA_PORT=9094
JWT_SECRET=benchmark-compose-placeholder
"@ | Set-Content $dependencyFile -Encoding utf8
# 压测应用由本脚本单独启动，Compose 只负责提供独立依赖，避免解析完整应用的必需配置。
docker compose -p $Project --env-file $dependencyFile up -d --wait mysql redis kafka | Out-Host
if ($LASTEXITCODE) { throw '压测依赖启动失败' }
$pidPath = Join-Path $output 'app.pid'
if (Test-Path $pidPath) {
    $previousId = [int](Get-Content $pidPath)
    $previous = Get-CimInstance Win32_Process -Filter "ProcessId=$previousId"
    if ($previous) {
        if (!$Restart) { throw '压测进程仍在运行，重启请传入-Restart' }
        if ($previous.Name -ne 'java.exe' -or $previous.CommandLine -notlike '*target/benchmark/application.properties*') { throw 'PID不属于压测应用，拒绝终止' }
        Stop-Process -Id $previousId
    }
}
@"
server.port=$Port
spring.datasource.url=jdbc:mysql://localhost:3307/opsflow?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
spring.datasource.password=$($credentials.password)
spring.data.redis.port=6380
spring.kafka.bootstrap-servers=localhost:9094
spring.kafka.listener.auto-startup=$((!$PauseNotifications.IsPresent).ToString().ToLowerInvariant())
opsflow.jwt.secret=$($credentials.jwt)
opsflow.bootstrap.enabled=true
opsflow.bootstrap.username=bench_admin
opsflow.bootstrap.password=$($credentials.password)
opsflow.assignment.enabled=false
opsflow.rate-limit.enabled=$($RateLimit.IsPresent.ToString().ToLowerInvariant())
opsflow.sla.enabled=$($Sla.IsPresent.ToString().ToLowerInvariant())
opsflow.ai.enabled=false
opsflow.ai.worker-enabled=true
opsflow.attachments.directory=target/benchmark/attachments
logging.file.name=target/benchmark/application.log
"@ | Set-Content (Join-Path $output 'application.properties') -Encoding utf8
$javaRoot = $env:JAVA_HOME
if (!$javaRoot) { throw '请先将当前PowerShell进程JAVA_HOME设为JDK25' }
$jar = Join-Path $root 'target/OpsFlow-0.0.1-SNAPSHOT.jar'
if (!(Test-Path $jar)) { throw '先运行mvnw verify生成JAR' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
# Windows运行中的JAR不能被Maven重命名，启动独立副本以免阻塞后续构建。
$runtimeJar = Join-Path $output "app-$stamp.jar"
Copy-Item -LiteralPath $jar -Destination $runtimeJar
$process = Start-Process -FilePath (Join-Path $javaRoot 'bin/java.exe') -ArgumentList @('-Xms512m','-Xmx1g','-jar',('"'+$runtimeJar+'"'),'--spring.config.import=optional:file:target/benchmark/application.properties') -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output "app-$stamp.out.log") -RedirectStandardError (Join-Path $output "app-$stamp.err.log")
$process.Id | Set-Content $pidPath
$base = "http://localhost:$Port"
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    if ($process.HasExited) { throw '压测应用已退出，请检查target/benchmark启动日志' }
    try { $ready = (Invoke-RestMethod "$base/actuator/health").status -eq 'UP' } catch { }
    if ($ready) { break }
    Start-Sleep -Seconds 1
}
if (!$ready) { throw '应用尚未就绪，保留进程以便排查，不自动重复启动' }
@{ startedAt = (Get-Date).ToUniversalTime().ToString('o'); port = $Port; sla = $Sla.IsPresent; rateLimit = $RateLimit.IsPresent; notificationsPaused = $PauseNotifications.IsPresent; aiWorkerEnabled = $true; externalAiEnabled = $false; heap = '512m/1g'; jarSha256 = (Get-FileHash $jar).Hash } | ConvertTo-Json | Set-Content (Join-Path $output "runtime-$stamp.json")
Write-Output "压测应用已就绪：$base，PID=$($process.Id)，独立项目$Project"
