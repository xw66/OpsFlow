param([ValidateSet('seed','create','list','detail','cold','race')][string]$Mode = 'list', [string]$Label = 'baseline', [int]$Vus = 10, [int]$Iterations = 1000, [string]$Duration = '30s', [int]$Offset = 0)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $root 'target/benchmark'
$image = 'grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec'
if ($Label -notmatch '^[a-zA-Z0-9_-]{1,20}$') { throw 'Label仅支持1至20位字母数字下划线和短横线' }
if ($Vus -lt 1 -or $Vus -gt 100) { throw '本机脚本限制1至100个VU' }
# 独立输出目录包含临时JWT，不能上传或提交整个目录。
docker run --rm -v "${root}/tests/k6:/scripts:ro" -v "${output}:/results" -e "MODE=$Mode" -e "RUN_LABEL=$Label" -e "VUS=$Vus" -e "ITERATIONS=$Iterations" -e "DURATION=$Duration" -e "OFFSET=$Offset" $image run /scripts/load.js
if ($LASTEXITCODE) { throw "k6阈值或执行失败：$Mode/$Label" }
