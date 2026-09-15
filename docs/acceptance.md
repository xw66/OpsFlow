# 交付验收清单

2026-09-15核对当前源码、V1–V9迁移、实际运行接口、180项后端测试、35项前端测试、完整Compose和独立压测输出。Vue真实界面、响应式检查及Chromium回归已纳入当前交付范围。

| 原始要求 | 实现与验收证据 |
|---|---|
| JDK25/Boot4稳定工程、Maven、IDEA、Compose | pom.xml、Maven wrapper、README；最终verify成功，当前JAR实际启动，47个OpenAPI路径，health=UP |
| 四角色、注册登录、JWT/RBAC | auth包及SecurityConfig；AuthIntegrationTest、SupportIntegrationTest验证密码、角色越权、停用和旧令牌权限 |
| CRUD、分类、优先级、标签、附件、交流 | TicketController/TicketService/AttachmentService，V3/V7；TicketIntegrationTest覆盖归属、版本、文件类型与事务回滚 |
| 七状态及受限重开、审计、版本条件更新 | TicketStatusTest、WorkflowIntegrationTest；真实MySQL并发接单与k6 32路竞争均只一个成功 |
| 可解释分配、排除离线、负载及最后分配时间 | WorkflowService/WorkflowMapper，组行锁与条件更新；无候选队列、跨组权限和游标扫描测试 |
| SLA快照、deadline、分页预警/违约/升级 | SlaIntegrationTest覆盖临界时间、多页、多实例、完成与扫描竞争；30条自然时间工单形成60条唯一事件，保留合法业务状态 |
| Kafka六类事件、事件ID、重试、消费幂等和异常记录 | EventEnvelope、OutboxPublisher、KafkaConfig、EventProcessor；MessagingIntegrationTest使用真实broker测试ACK后恢复、重复消费、DLT和授权重放 |
| Outbox与工单本地事务一致、租约、多实例竞争 | TicketIntegrationTest、MessagingIntegrationTest、SlaIntegrationTest；压测4171条工单无缺失初始历史/创建事件 |
| Redis热点缓存、幂等、简单限流 | RedisIntegrationTest真实Redis覆盖并发租约、原始响应重放、事务后失效、权限和降级；故障脚本验证数据库兜底及登录503/恢复200 |
| AI分类/摘要/草稿、结构化输出、枚举长度校验、超时重试 | AiModelServiceTest以真实Spring AI客户端连接本地契约服务；AiIntegrationTest验证任务租约、输入边界、失败隔离、显式采纳及版本冲突 |
| AI日志、Token未知值、人工确认、不自动关闭 | AiWorkService/AiModelService/AiAnalysisService；调用在事务外，供应商无Token时为null，回复仅确认后写公开评论；真实外部模型效果仍未验证 |
| 每日新增、分类、处理中、响应/解决均值、SLA达成率、超时数、AI采纳率 | StatisticsMapper/Service和V9；8项统计集成测试覆盖时区、空样本、取消/重开、权限、重复事件、回滚及历史分类跨组刷新 |
| 参数和数据权限、统一响应/异常、中文关键注释 | 控制器Bean Validation、Security与服务层归属/状态检查；GlobalExceptionHandlerTest及各业务HTTP测试；无前端绕过授权路径 |
| 必需7类测试 | 状态机、并发接单、重复消费、Outbox回滚、SLA扫描、AI输出校验、接口权限均有对应真实或契约测试；当前后端180项，0失败/错误/跳过 |
| k6创建吞吐、列表P95、接单成功率 | results/create-create01.json、list-list02.json、race-race01.json；保留seed-seed01和list-list01失败结果 |
| SLA扫描耗时、Kafka积压/恢复、依赖故障、数据库核对 | performance.md、sla-scan.log、paused/recovery.jsonl、fault-*.json、final-facts.jsonl |
| 实测后修复并复测、SQL计划及版本追踪 | 共享标签间隙锁死锁修复，1000次同并发负载复测成功；explain.sql/explain-final.txt、运行JAR哈希及源码SHA-256清单；仓库无HEAD，明确记为null |
| 文档、阶段更新、演示和真实简历材料 | development-plan.md、database-design.md、verification.md、architecture.md、performance.md、resume.md、tests/k6/README.md |

最终测试摘要归档results/tests-final.json；当前回归输出为 `target/final-regression-20260915.log`，前端和浏览器证据见 `target/final-frontend-*`、`target/final-browser-20260915.log`。原始测试计数包含参数化状态检查，不将180项解释为180个独立功能。

明确未宣称：真实企业上线规模、生产高可用、长期稳定吞吐、AI准确率/费用、专业文件杀毒、工作日历及暂停SLA。模型凭据缺失不阻塞工单；故障轮次和AI任务吞吐上限公开保留。以上不是用模拟数据冒充实测，而是本次第一版交付的已知边界。

## 2026-09-15：本次缺口收口

| 本次原始需求 | 完成确认 |
|---|---|
| 1. AI 编辑、草稿保护和采纳后刷新 | 回复建议编辑值会进入公开回复草稿；已有草稿需显式确认才会替换；采纳后刷新工单详情。`ai.test.ts` 覆盖。 |
| 2. 统计指标、日期与刷新口径 | 看板补显示分类、客服处理中量和服务组工作量；默认范围包含上海当天（结束日期为次日）；页面显示数据截点和日报刷新时间，并明确“待刷新不等于 0”。 |
| 3. 通知、统计、AI 的请求竞争和权限失效 | 三页均用 `AbortController` 隔离旧请求；401/403 后清空旧数据，AI 还处理 404。`dashboard.test.ts` 覆盖权限失效。 |
| 4. 测试和浏览器验收 | 前端 Vitest 10 文件、65 项通过；`mvnw.cmd verify -B -ntp` 为 181 项通过、0 失败/错误/跳过；隔离 Chromium 8 项通过，结果在 `target/e2e/browser-results.json`。 |
| 5. 文档与验收路径 | Playwright 默认凭据、端口和验收 README 已统一为 `target/acceptance`、8182、5274，避免准备环境与浏览器跑到不同实例。 |
