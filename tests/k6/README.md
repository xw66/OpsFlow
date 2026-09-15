# OpsFlow 本地压测

仅对本机独立的 `opsflow-bench` Compose 项目执行。MySQL/Redis/Kafka端口3307/6380/9094，应用8081；脚本不删除开发数据或压测卷。先运行完整Maven回归生成JAR，当前PowerShell的JAVA_HOME必须指向JDK25。

```powershell
./tests/k6/prepare.ps1 -Port 8091 -Project opsflow-bench-20260915
./tests/k6/fixture.ps1 -BaseUrl http://localhost:8091
./tests/k6/run.ps1 -Mode race -Label race01 -Vus 32
./tests/k6/run.ps1 -Mode seed -Label seed01 -Iterations 1000 -Vus 10
./tests/k6/run.ps1 -Mode create -Label create01 -Duration 30s -Vus 10
./tests/k6/run.ps1 -Mode list -Label list01 -Duration 30s -Vus 10
./tests/k6/run.ps1 -Mode detail -Label detail01 -Duration 30s -Vus 10
```

每轮使用新的Label；重复Label会复用创建幂等键，不能作为真实创建压测。接单脚本对同一已分配工单执行每VU一次操作，阈值要求1次成功、其余409；再次竞争需重新执行fixture生成新工单。fixture通过真实HTTP完成注册、角色、组织、分类、SLA规则、创建到关闭的流程，并验证7条状态历史。

prepare默认关闭自动分配、SLA扫描及限流。AI工作任务开启，但外部模型关闭，任务逐条记录DISABLED。Kafka/Outbox/通知和统计仍运行，Redis缓存和创建幂等仍启用；该条件不能冒充全部任务开启的生产容量。`prepare.ps1 -Restart -Sla`开启SLA调度，`-RateLimit`开启限流。调度和功能正确性另有真实组件集成测试。

脚本使用固定镜像摘要的k6 v2.2.0。输出在target/benchmark，JSON包含配置、样本量、P95/P99、检查及阈值结果；任意非预期HTTP错误或业务检查失败导致命令失败。先核对数据库事实，再引用结果。此目录还包含随机凭据和临时JWT，**不要整体上传或提交**。

默认10VU/30秒仅是本机初始测试条件，不能证明长期稳态、峰值容量或生产SLO。数据生成、应用预热、稳定负载与故障恢复应分别记录。详情缓存与列表查询是不同接口，不用详情命中时间替代列表P95。

依据：[Grafana运行k6](https://grafana.com/docs/k6/latest/get-started/running-k6/)、[执行器](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/)。

## SLA和故障恢复

```powershell
./tests/k6/sla-fixture.ps1 -Count 30
# 等待真实一分钟deadline，再启用扫描；只暂停通知组，AI消费组仍运行。
./tests/k6/prepare.ps1 -Restart -Sla -PauseNotifications -Port 8091 -Project opsflow-bench-20260915
./tests/k6/run.ps1 -Mode seed -Label backlog01 -Iterations 300
./tests/k6/monitor.ps1 -Label paused -Samples 6
# 后台监测与应用重启重叠，先完成本轮监测，再开始其他故障演练。
$monitorJob = Start-Job -FilePath ./tests/k6/monitor.ps1 -ArgumentList 'recovery',15,2
./tests/k6/prepare.ps1 -Restart -Sla -RateLimit
Wait-Job $monitorJob | Receive-Job
./tests/k6/fault.ps1 -Dependency redis -BaseUrl http://localhost:8091 -Project opsflow-bench-20260915
./tests/k6/fault.ps1 -Dependency kafka -BaseUrl http://localhost:8091 -Project opsflow-bench-20260915
```

monitor的原始Kafka CLI输出及JSONL同时保留；CLI失败或分区数不完整时lag为null，不填0。跨系统采样不是原子快照，应按时间窗口解释。fault脚本仅停止固定opsflow-bench容器，并在finally中重启；验证Redis故障时业务回源/登录拒绝及恢复，Kafka失败重试记录和恢复消费。

冷详情需要先通过sql.ps1选择本轮用户尚未读取的不同工单ID，将JSON数组保存为target/benchmark/cold-ids.json，确认Redis没有这些工单缓存键，再执行 `run.ps1 -Mode cold -Label cold01`。该模式每个ID只读取一次，与反复读取同一个详情的detail模式不同。不要将已缓存的ID集合称为冷缓存。

