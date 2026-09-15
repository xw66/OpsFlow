param([Parameter(Mandatory)][string]$Sql, [string]$Project = 'opsflow-bench')
$ErrorActionPreference = 'Stop'
# 仅连接固定的独立压测容器，密码在容器内展开，不出现在命令行和输出中。
$Sql | docker exec -i "$Project-mysql-1" sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u opsflow -D opsflow --batch --raw --skip-column-names'
if ($LASTEXITCODE) { throw '压测SQL执行失败' }
