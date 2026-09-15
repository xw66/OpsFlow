# 前端接口契约与实现边界

## 已实现的工作台读取接口

`GET /api/workspace/tickets`默认`view=MINE_CREATED`。所有视图在数据库授权范围内查询，不能通过参数扩大权限。

| 参数 | 取值与语义 |
|---|---|
| view | MINE_CREATED由我提交；MINE_ASSIGNED分配给当前客服；MY_GROUP组长所管理的组或管理员全部组；ACCESSIBLE全部已授权工单 |
| groupId | 可选正数，与视图及资源权限取交集 |
| queue | 默认ALL；MANUAL为CREATED且未分配；ESCALATED为升级级别大于0且仍处于CREATED/ASSIGNED/PROCESSING/PENDING。非ALL仅允许MY_GROUP视图 |
| status/categoryId/priority | 可选服务端过滤 |
| keyword | 最长200字符，标题字面子串或完整数字ID；百分号不作为通配符 |
| sla | ALL；WARNING当前未完成时限临近；BREACHED当前未完成时限已到期 |
| offset/limit | 0–100000 / 1–100；默认0/20 |

返回`{items,hasMore,offset,limit}`，列表一次联表取提交人、分类、组及客服名称。无总数和假计数。由我提交优先待补充，其后按创建时间降序；工作视图优先未完成时限，再按期限升序。SLA筛选不把历史违约标记当作当前仍超时。

`GET /api/workspace/tickets/{id}`返回`{detail:{ticket,tags},display,staff,manager}`，其中display沿用列表行格式，manager表示当前用户为管理员或实际管理该工单客服组的组长。事务内直接读取数据库，供页面加载、写操作后的刷新及409冲突恢复使用。原`/api/tickets/{id}`的热点缓存接口保留。

`GET /api/workspace/tickets/{id}/assignment-candidates`返回`{items,hasMore,offset,limit,ticketVersion}`。支持可选正数groupId、最长64字符keyword（账号/姓名/组名称的字面子串）、offset=0–100000及limit=1–100，默认0/20。items包含userId、username、displayName、groupId、groupName、online、activeCount、lastAssignedAt。仅返回在线、账号及客服启用、目标组启用且仍具AGENT角色的候选，排除当前负责人；按活动量、最后分配时间、用户ID排序。活动量包含ASSIGNED、PROCESSING、PENDING。

普通提交人无候选查询权限。CREATED仅允许实际管理者查询当前组候选；ASSIGNED/PROCESSING/PENDING允许有工单工作权限的人员查询，同组可转派，跨组要求管理员或同时管理源组及目标组的组长。其余状态返回409。groupId只能缩小范围。前端依据ticketVersion确认候选快照，提交仍走已有assign/transfer接口并重新检查资格和版本。

`GET /api/support/categories/{id}/priorities`返回启用分类、启用客服组与启用SLA规则交集中的`{priority,responseMinutes,resolveMinutes}`。空数组表示当前不能用该分类创建工单。提交时仍以服务端实时校验为准。

## F1组件边界

`GET /api/support/groups/{id}/workload`仅管理员或管理该组的组长可读。offset/limit范围与工作台一致，返回`{items,hasMore,offset,limit}`。成员包含userId、username、displayName、online、available、activeCount、processingCount。available表示账号、客服、组启用且仍有AGENT角色，实际可接单还需online=true。保留离线、停用及零负载成员；两项数量只统计当前组内工单，activeCount包括ASSIGNED/PROCESSING/PENDING，processingCount仅PROCESSING，不暴露成员在其他组遗留工单的数量。

- App：认证状态与导航外壳，组合路由页面。
- AuthForm：登录/注册字段与提交状态；认证状态只在内存保存。
- TicketListPage：组合筛选和列表，处理取消请求与服务端分页。
- TicketFilters：接收分类和筛选值，发出应用筛选事件。
- TicketList：接收items/loading/hasMore，呈现条目并发出翻页事件。
- TicketCreatePage：编排不可变创建快照、幂等重试和已创建事实。
- TicketForm：字段校验、分类优先级联动、标签和文件选择，发出创建请求。
- TicketDetailPage：组合详情、工作流、交流及附件；useTicketDetail负责读取、请求作用域和版本冲突。
- ReplyComposer：分别持有公开与内部草稿，发出发送事件；仅成功后清空对应草稿，失去权限不能自动转为公开内容。
- TicketAssignment：按工单授权查询分页候选，保留待确认版本与说明，明确展示目标与跨组影响后提交。
- TicketActions：保存动作及版本快照；刷新后不自动把原动作换成另一种操作。
- TicketAttachments：授权下载与顺序上传，失败保留待重试文件。
- TicketEdit：复用TicketForm并持有原始输入/版本，冲突时保留草稿，经用户确认后接受新版本。
- TicketHistoryPanel：展开后分页查询状态/分配记录，工单版本或权限变化时丢弃旧结果。
- TicketQueue：统一用户与客服列表的筛选、分页、鉴权请求及错误处理；视图仍由服务端限制。

编辑沿用 `PUT /api/tickets/{id}`，请求`{ticket:{title,description,categoryId,priority,tags},version}`。只允许CREATED提交人/管理员；不改分类/优先级时保留原SLA快照，规则已停用仍可改文字；改变组合时服务端重新验证，期限从原始创建时间计算。

附件删除沿用 `DELETE /api/tickets/{id}/attachments/{attachmentId}?version=...`，只允许上传者或管理员且非CLOSED/CANCELLED；确认文件及版本后提交，冲突刷新但不自动重试。

`/api/tickets/{id}/comments`及新增评论结果增加authorName；`/history`增加operatorName；`/assignments`增加fromAssigneeName、toAssigneeName、fromGroupName、toGroupName和operatorName。名称是当前资料的联表显示值，原操作人/组ID仍是历史依据；系统自动操作的operatorId/operatorName为null。既有字段和权限规则保留。

客服工作台使用`view=MINE_ASSIGNED`，详情动作调用现有`/api/tickets/{id}/assign|transfer|accept|suspend|resume|resolve|close|reopen`并携带待确认version与原因；客服备注通过现有评论接口的`internal=true`写入，普通用户不会收到。组长团队视图、管理员用户搜索及通知未读数按后续阶段实现，当前不伪造占位数据。

未保存提示覆盖离开页面、工单路径参数切换、主动退出及切换账号。同账号重新认证保留草稿；切换账号确认后卸载原页面。详情失去访问权时清除服务端内容，保留不可发送的本地回复与操作草稿；请求失效或组件卸载后，不追加旧交流记录、不继续旧附件队列。

## 验证

2026-09-14：`TicketWorkspaceIntegrationTest`5项、`TicketIntegrationTest`12项、`SupportIntegrationTest`6项通过，共23项。真实MySQL8.4容器验证角色/资源范围、名称映射、跨页筛选、SLA当前时限、实时版本、输入边界与有效优先级。前端9项行为测试通过，生产构建通过。后端全量`mvn verify`共174项通过，原始日志`target/all-tests-20260914.log`。

2026-09-14 后续执行：新增权限/草稿与分配转派后，前端24项行为测试、类型检查及生产构建通过。工作台与工作流专项16项通过（工作台8项、工作流8项），真实MySQL验证候选排序/分页、离线停用与撤角色排除、跨组双方管理权、候选读取后离线不能写入；日志`target/assignment-candidates-test.log`。完整后端回归另行记录，不把组件测试算作浏览器E2E。

本轮最终结果：后端177项完整回归通过并打包，前端25项测试、类型检查与构建通过。原始记录见`target/frontend-workflow-full-20260914.log`、`target/frontend-workflow-tests-20260914.log`和`target/frontend-workflow-build-20260914.log`。真实浏览器与性能复测仍待执行。

第3模块后续验收：前端30项、后端177项通过，类型检查及构建通过；隔离真实依赖的3个Chromium场景通过，包含四角色完整状态链路、双窗口竞争及手机注册/取消。见 [验证记录](verification.md) 和 [浏览器回归说明](../tests/e2e/README.md)。
