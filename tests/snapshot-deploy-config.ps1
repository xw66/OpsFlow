$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
$app = 'opsflow-deploy-app-1'
$jar = (Resolve-Path (Join-Path $root 'target/OpsFlow-0.0.1-SNAPSHOT.jar')).Path -replace '\\', '/'
function Invoke-Docker { param([Parameter(ValueFromRemainingArguments = $true)] [string[]]$Arguments)
    $result = & docker @Arguments
    if ($LASTEXITCODE) { throw "Docker命令失败：$($Arguments -join ' ')" }
    return $result
}

$output = Join-Path $root 'target/deployment'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$snapshotFile = Join-Path $output 'opsflow-deploy.snapshot.compose.json'
if (Test-Path -LiteralPath $snapshotFile) { throw '私有部署快照已存在；拒绝覆盖原始基线' }
$container = (Invoke-Docker inspect $app | ConvertFrom-Json)[0]
if ($container.Config.Labels.'com.docker.compose.project' -ne 'opsflow-deploy') { throw '目标不是opsflow-deploy应用容器' }
if ($container.Config.Entrypoint -join ' ' -ne 'java -jar /app/app.jar') { throw '应用入口与预期不符，拒绝生成快照' }

$originalEnvironment = [ordered]@{}
$environment = [ordered]@{}
foreach ($entry in $container.Config.Env) {
    $separator = $entry.IndexOf('=')
    if ($separator -le 0) { throw '容器环境变量格式无效' }
    $key = $entry.Substring(0, $separator)
    $originalEnvironment[$key] = $entry.Substring($separator + 1)
    # Compose会插值$，快照中必须双写以便还原容器的原始值。
    $environment[$key] = $originalEnvironment[$key].Replace('$', '$$')
}
$mounts = @()
$volumes = [ordered]@{}
foreach ($mount in $container.Mounts) {
    if ($mount.Type -eq 'volume') {
        if ([string]::IsNullOrWhiteSpace($mount.Name)) { throw '命名卷信息不完整' }
        $source = $mount.Name
        $volumes[$mount.Name] = @{ external = $true }
    } elseif ($mount.Type -eq 'bind') {
        if ([string]::IsNullOrWhiteSpace($mount.Source)) { throw '绑定挂载源路径不完整' }
        $source = $mount.Source -replace '\\', '/'
    } else { throw "不支持的挂载类型：$($mount.Type)" }
    $mounts += "$source`:$($mount.Destination):$(if ($mount.RW) { 'rw' } else { 'ro' })"
}
$mounts += "$jar`:/app/app.jar:ro"

$networks = [ordered]@{}
$serviceNetworks = [ordered]@{}
foreach ($property in $container.NetworkSettings.Networks.PSObject.Properties) {
    $name = $property.Name
    if ([string]::IsNullOrWhiteSpace($name)) { throw '网络名称为空' }
    $networks[$name] = @{ external = $true }
    $aliases = @($property.Value.Aliases | Where-Object { ![string]::IsNullOrWhiteSpace($_) })
    $serviceNetworks[$name] = if ($aliases.Count) { @{ aliases = $aliases } } else { @{} }
}
if ($networks.Count -eq 0) { throw '容器没有网络配置' }

$ports = @()
foreach ($property in $container.HostConfig.PortBindings.PSObject.Properties) {
    $parts = $property.Name -split '/', 2
    if ($parts.Count -ne 2) { throw '端口绑定格式无效' }
    foreach ($binding in $property.Value) {
        if ([string]::IsNullOrWhiteSpace($binding.HostPort)) { throw '端口发布配置不完整' }
        $ports += @{ target = [int]$parts[0]; published = [string]$binding.HostPort; host_ip = [string]$binding.HostIp; protocol = $parts[1]; mode = 'host' }
    }
}
if ($ports.Count -eq 0) { throw '容器没有端口绑定' }
$restart = if ([string]::IsNullOrWhiteSpace($container.HostConfig.RestartPolicy.Name)) { 'no' } else { $container.HostConfig.RestartPolicy.Name }
$appConfig = [ordered]@{ image = $container.Config.Image; entrypoint = @($container.Config.Entrypoint); working_dir = $container.Config.WorkingDir; environment = $environment; ports = $ports; volumes = $mounts; networks = $serviceNetworks; restart = $restart }
if (![string]::IsNullOrWhiteSpace($container.Config.User)) { $appConfig.user = $container.Config.User }
$snapshot = [ordered]@{ services = @{ app = $appConfig }; networks = $networks; volumes = $volumes }
$snapshot | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $snapshotFile -Encoding utf8

$resolved = Invoke-Docker -Arguments @('compose', '--project-directory', $root, '-p', 'opsflow-deploy', '-f', $snapshotFile, 'config', '--format', 'json') | ConvertFrom-Json
$resolvedEnvironment = $resolved.services.app.environment
$mismatch = @($originalEnvironment.Keys | Where-Object { $null -eq $resolvedEnvironment.$_ -or $resolvedEnvironment.$_ -cne $originalEnvironment.$_ })
if ($mismatch.Count) { throw "Compose环境值与容器快照不一致：$($mismatch.Count)项" }
if (@($resolved.services.app.ports).Count -ne $ports.Count -or @($resolved.services.app.volumes).Count -ne $mounts.Count -or @($resolved.services.app.networks.PSObject.Properties).Count -ne $networks.Count -or $resolved.services.app.restart -ne $restart) { throw 'Compose端口、挂载、网络或重启策略与快照不一致' }
$probe = @(@('plain', '$', '$$', '${NAME}', '#', "line1`nline2"), @('plain', '$$', '$$$$', '$${NAME}', '#', "line1`nline2"))
for ($i = 0; $i -lt $probe[0].Count; $i++) { if ($probe[0][$i].Replace('$', '$$') -cne $probe[1][$i]) { throw 'Compose美元符转义自检失败' } }
Write-Output "私有部署快照已生成：环境值$($environment.Count)项完全一致，端口$($ports.Count)项，挂载$($mounts.Count)项，网络$($networks.Count)项"
