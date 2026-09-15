# OpsFlow 数据库设计

本文件是目标设计。V1 已实现用户、角色及审计表；V2 已实现客服组、客服、分类和 SLA 规则表；V3 已实现工单、状态历史、评论、标签、附件及 Outbox；V4 增加工单解决周期及分配历史；V5 增加 Outbox 租约、消费去重、失败记录和站内通知；V6 增加 SLA 历史、累计违约标记及升级级别；V7 增加创建请求键、摘要与响应快照；V8 增加AI分析任务及调用日志；V9增加日报队列、按组日快照和创建日索引。阶段01–12已经验收当前范围，实际结果见 verification.md。

## 通用约定

- InnoDB、utf8mb4；业务主键 BIGINT 自增，eventId 使用 UUID 字符串，唯一键使用一致的二进制比较规则。
- 时间使用 UTC `DATETIME(6)`，Java 使用 `Instant` 并显式配置 JDBC 时区；业务日报按 Asia/Shanghai 转换边界。
- 状态和角色使用 VARCHAR + 服务端枚举校验；非空、唯一、外键及必要 CHECK 约束在迁移中落地。
- 核心数据停用或取消，不物理删除；日志追加写。外键不级联删除历史记录。
- 可变工单含 `version BIGINT NOT NULL DEFAULT 0`；涉及工单变化的服务事务同时写审计和 Outbox。

## 核心表

| 表 | 关键字段 | 主要约束/索引 |
|---|---|---|
| app_user | id、username、password_hash、display_name、enabled、created_at、updated_at | UNIQUE(username)，不用 user 命名避免歧义 |
| role | id、code、name | UNIQUE(code)，USER/AGENT/LEADER/ADMIN |
| user_role | user_id、role_id | 联合主键，分别关联用户与角色 |
| support_group | id、name、leader_id、enabled | leader_id 关联用户；负责人必须具备 LEADER 角色 |
| support_agent | id、user_id、group_id、online、enabled、last_assigned_at | UNIQUE(user_id)，INDEX(group_id, enabled, online) |
| ticket_category | id、code、name、group_id、enabled | UNIQUE(code)，group_id 关联客服组 |
| sla_policy | id、category_id、priority、response_minutes、resolve_minutes、auto_escalate、enabled | UNIQUE(category_id, priority)，正数时限 |
| ticket | id、user_id、category_id、group_id、assignee_id、title、description、priority、status、version、created_at、updated_at | 见下方专用索引 |
| ticket_assignment | id、ticket_id、from_assignee_id、to_assignee_id、operator_id、reason、ticket_version、created_at | INDEX(ticket_id, created_at)，UNIQUE(ticket_id, ticket_version) |
| ticket_comment | id、ticket_id、author_id、content、internal、created_at | INDEX(ticket_id, created_at)，草稿不写本表 |
| ticket_status_history | id、ticket_id、from_status、to_status、operator_id、remark、ticket_version、created_at | INDEX(ticket_id, created_at)，UNIQUE(ticket_id, ticket_version) |
| ticket_tag | id、name | UNIQUE(name) |
| ticket_tag_relation | ticket_id、tag_id | 联合主键 |
| ticket_attachment | id、ticket_id、uploader_id、original_name、storage_key、content_type、size_bytes、created_at | UNIQUE(storage_key)，INDEX(ticket_id) |
| sla_event | id、ticket_id、sla_cycle、type、deadline、observed_at、event_id | UNIQUE(ticket_id, sla_cycle, type)，UNIQUE(event_id)；响应固定周期1，解决使用当前周期，event_id 与 Outbox 一致 |
| ticket_ai_analysis | id、ticket_id、ticket_version、kind、status、source_event_id、source_event_hash、input_text、categories_json、result_json、requested_by、accepted_by、accepted_at、created_at、finished_at、lease_owner、lease_until | V8实现；UNIQUE(ticket_id,ticket_version,kind)，UNIQUE(source_event_id)，索引(status,id)/(status,lease_until) |
| ai_call_log | id、analysis_id、invocation_id、model、input_tokens、output_tokens、duration_ms、attempts、outcome、error_code、applied、created_at | V8实现；UNIQUE(invocation_id)，INDEX(analysis_id,created_at)，token 可为空，applied区分过期租约结果 |
| outbox_event | event_id、aggregate_id、aggregate_version、event_type、schema_version、payload、status、attempts、available_at、lease_owner、lease_until、last_error、created_at、sent_at | event_id 主键，INDEX(status, created_at)，INDEX(status, available_at, event_id)，INDEX(status, lease_until) |
| consumed_event | consumer_name、event_id、payload_hash、processed_at | 联合主键，幂等记录和数据库副作用同事务；SHA-256 拒绝同 ID 不同内容 |
| consumer_failure | id、consumer_name、event_id、topic、partition_no、offset_no、attempts、error_type、payload、status、created_at、resolved_at | UNIQUE(consumer_name, topic, partition_no, offset_no)，INDEX(consumer_name, event_id)，仅存异常类型，原始消息限管理员查询 |
| notification | id、recipient_id、event_id、ticket_id、content、read_at、created_at | UNIQUE(recipient_id, event_id)，站内通知 |
| audit_log | id、operator_id、action、resource_type、resource_id、before_json、after_json、remark、created_at | INDEX(resource_type, resource_id, created_at)，敏感字段不落日志 |

自动任务的 operator_id 允许为空，并在 action/remark 中说明系统操作，不能伪造某位用户为操作人。

## ticket 补充字段

阶段10的V9迁移增加以下日报结构，专项测试已验证真实MySQL迁移；阶段完整验收以verification.md为准。

| 表/索引 | 用途与约束 |
|---|---|
| statistics_day | business_date主键、requested待刷新标记、refreshed_at；索引(requested,business_date)，任务行锁保护重算 |
| statistics_daily | 联合主键(business_date,group_id)，created_count；group_id外键，按事实表重算而非消费消息累加 |
| ticket(created_at,group_id) | V9新增idx_ticket_created_group，用于按创建日聚合；实际执行计划与性能待压测记录 |

| 字段 | 用途 |
|---|---|
| sla_policy_id、response_minutes、resolve_minutes、auto_escalate | 创建时规则快照，后续规则修改不追溯改变历史工单 |
| response_deadline、resolve_deadline | 明确截止时间，供索引扫描 |
| first_response_at、resolved_at、closed_at、cancelled_at | 首次响应与当前周期处理时间 |
| response_breached、resolve_breached、escalation_level | SLA 累计标记与升级层级，独立于业务状态；V6 CHECK 限级别0或1，重开保留 |
| sla_cycle、cycle_started_at | 重开时递增解决周期，历史违约保存在 sla_event；工单违约标记保留累计事实 |
| create_request_key、create_request_hash、create_response | V7实现，UNIQUE(user_id, create_request_key)，键使用ascii_bin区分大小写；SHA-256绑定请求内容，JSON保存首次响应，随工单长期保留；旧数据允许为空 |

必需索引：

```sql
CREATE INDEX idx_ticket_user_created ON ticket(user_id, created_at);
CREATE INDEX idx_ticket_assignee_status ON ticket(assignee_id, status);
CREATE INDEX idx_ticket_status_response ON ticket(status, response_deadline);
CREATE INDEX idx_ticket_status_resolve ON ticket(status, resolve_deadline);
CREATE INDEX idx_ticket_category_priority ON ticket(category_id, priority);
CREATE INDEX idx_ticket_group_status ON ticket(group_id, status);
```

扫描使用 `ORDER BY deadline, id` 的游标分页；由 EXPLAIN 和真实数据量判断是否扩展覆盖索引，第一版不盲目叠加索引。

## 事务与并发约束

状态规则先校验，持久化再执行条件更新。以下是设计示例，实际 SQL 使用 MyBatis 绑定参数，不拼接用户输入：

```sql
UPDATE ticket
SET status = :targetStatus, version = version + 1, updated_at = :now
WHERE id = :ticketId AND status = :expectedStatus AND version = :expectedVersion;
```

影响行数为 0 时整个事务失败并返回 409；操作人的角色和工单归属在业务层校验。成功更新、历史记录及 Outbox 必须在同一事务提交。

自动分配使用分类组内的短数据库事务锁串行选择候选人，拿锁后读取最新负载，并用工单条件更新兜底。所有自动分配和人工转派遵守一致锁顺序；跨组操作按组 ID 排序加锁，降低死锁风险，死锁重试有上限。

Outbox 提供至少一次投递，不声称 Kafka 与 MySQL 跨系统恰好一次。消费者数据库副作用和 consumed_event 原子提交；外部模型调用无法纳入该事务，分析结果落库幂等，仍可能重复计费，必须在调用记录中可追踪。

## 状态与统计口径

- 正常状态只允许需求中的七条边。重新打开是独立动作，只允许 LEADER/ADMIN 对 RESOLVED/CLOSED 执行，组长限本组，原因必填。
- 转派不伪装成非法普通状态转换：ASSIGNED 保持 ASSIGNED，PROCESSING/PENDING 保持当前状态，接手责任变化记入 assignment 和审计。
- 首次响应从创建到首个有效客服公开回复/处理响应；仅分配或接单不算响应，具体处理接口必须形成可见反馈。
- 平均首次响应只纳入已经响应的工单，并同时返回样本数。
- 平均解决时间按完成周期的解决时间减周期开始时间计算，首周期起点为创建时间，重开周期另算。
- SLA 达成率第一版按已评估完成的解决周期计算：按时解决且首响达标的周期 / 全部已解决的有效周期；重开周期不重复考核首响。尚未解决的超时工单单列，不能从报表中消失。
- 取消工单不纳入解决达成率，但保留已发生 SLA 事件；报表必须展示取消量。
- AI 分类采纳率为被人工采纳的分类建议数 / 成功生成的分类建议数，返回样本数；失败调用率单独统计。
- 查询本组数据使用当前 group_id，历史分组统计使用事件/周期归属快照，避免转派篡改历史报表口径。

