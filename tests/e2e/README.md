# 真实浏览器回归

在项目根目录执行，JAVA_HOME 使用 IDEA 对应的 JDK 25：

```powershell
.\mvnw.cmd verify
.\tests\e2e\prepare.ps1
cd frontend
npm.cmd ci
npx.cmd playwright install chromium
npm.cmd run e2e
```

准备脚本创建独立 Compose 项目 `opsflow-acceptance`：MySQL 3318、Redis 6391、Kafka 9106、后端 8182。Playwright 启动独立 Vite 5274，经同源 `/api` 代理到后端；不复用其他开发服务器。已有验收应用可直接重复运行测试；更新 JAR 后，在项目根目录执行 `tests/e2e/prepare.ps1 -Restart`。仅当 PID 与验收 Java 命令匹配时才允许重启。

随机密码、JWT 密钥、应用配置、PID 与运行信息放在被 Git 忽略的 `target/acceptance/`。不要打印或提交这些文件。测试不记录网络 trace，避免报告保存登录凭据；错误截图只含隔离演示数据。无需真实 AI Key。

测试通过正式接口注册独立用户并配置角色、客服组、分类及 SLA。每轮使用不同用户名，不删除开发或历史压测数据。真实 UI 覆盖注册、登录、建单、编辑、上传/删除附件、人工分配、在线状态、接单、内部/公开交流、挂起/恢复、解决/关闭、受限重开及手机取消。双浏览器竞争测试核对实际 HTTP 200/409 和数据库历史接口仅一次接单。

此环境保留真实 MySQL、Redis、Kafka、Outbox、消费者与 SLA 调度，但关闭自动分配及接口限流，使 CREATED 编辑、手工分配与多角色登录可重复复现。自动分配与限流仍由后端真实依赖集成测试验证；本 E2E 不替代压测、故障恢复或外部模型效果验证。

浏览器原始结果为 `target/e2e/browser-results.json`，失败截图在 `frontend/test-results/`，演示页面截图在 `docs/results/screenshots/`。测试配置依据 [Playwright 配置](https://playwright.dev/docs/test-configuration) 与 [Web Server 集成](https://playwright.dev/docs/test-webserver)。当前先验收 Chromium；其余浏览器的支持不能由此推定。

停止依赖时从项目根目录执行：

```powershell
docker compose -p opsflow-acceptance --env-file target/acceptance/dependencies.env stop
```

应用进程应核对 `target/acceptance/app.pid` 对应 Java 命令确为 `target/acceptance/application.properties` 后停止；停止依赖不会停止 Java。不要将不明 PID 或默认开发数据库用于清理操作。
