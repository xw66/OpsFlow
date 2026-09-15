# 启动与配置

## Docker Compose

准备 Docker 引擎，首次运行时复制环境配置：

```powershell
Copy-Item .env.example .env
```

在 `.env` 中设置 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`，并添加随机 JWT 签名密钥。已有环境文件可直接编辑。

PowerShell 7 生成密钥：

```powershell
$jwtBytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
Add-Content .env ('JWT_SECRET=' + [Convert]::ToBase64String($jwtBytes))
```

首次管理员通过以下变量初始化，密码使用自行设置的至少十位密码：

```properties
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_USERNAME=ops_admin
BOOTSTRAP_ADMIN_PASSWORD=填写本地管理员密码
```

启动完整应用：

```powershell
docker compose up -d --build
docker compose ps
```

| 地址 | 用途 |
| --- | --- |
| `http://localhost:8081` | 前端工作台 |
| `http://localhost:8080/swagger-ui/index.html` | 接口文档 |
| `http://localhost:8080/actuator/health` | 健康检查 |

前端由 Nginx 代理后端请求。MySQL、Redis、Kafka 和附件使用 Docker 卷保存。初始化管理员后可将 `BOOTSTRAP_ADMIN_ENABLED` 改为 `false`。本地 `.env` 已被 Git 忽略。

## 建立业务配置

1. 注册业务用户、客服与组长账号。
2. 管理员在“用户账号”中分配客服、组长角色。
3. 创建客服组并指定组长，添加组内客服。
4. 创建工单分类，将分类关联到客服组。
5. 为分类和优先级配置 SLA 规则。
6. 客服切换为在线状态，用户即可提交工单并进入分配流程。

## 本地开发

后端使用 JDK 25，IDEA 的项目 SDK、Maven 导入器和运行器均选择该版本。启动基础依赖：

```powershell
docker compose up -d --wait mysql redis kafka
.\mvnw.cmd spring-boot:run
```

默认连接 MySQL 3306、Redis 6379 和 Kafka 9092；应用读取工作目录的 `.env`。修改 MySQL 映射端口后，同步配置本地 JDBC 连接，例如：

```properties
DB_URL=jdbc:mysql://localhost:3310/opsflow?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
```

前端使用 Node.js 24.12.0 或更高版本：

```powershell
cd frontend
npm ci
npm run dev
```

Vite 默认代理到 `http://127.0.0.1:8080`，可通过 `OPSFLOW_API_URL` 调整。容器前端和本地开发前端分别使用各自端口。

## 配置智能辅助

在 `.env` 中填写模型服务配置，并重启后端：

```properties
AI_ENABLED=true
AI_BASE_URL=模型服务的兼容接口地址
AI_MODEL=模型名称
AI_API_KEY=本地模型密钥
AI_TIMEOUT_MS=10000
```

模型需支持聊天接口和 JSON 结构化输出。工单详情可请求分类、摘要和回复建议，结果由服务端校验后展示。

## 运行测试

后端测试通过 Testcontainers 启动独立数据库与消息依赖，执行前保持 Docker 引擎可用：

```powershell
.\mvnw.cmd verify
cd frontend
npm test
npm run build
```

浏览器回归与压测分别使用独立数据环境，参见[浏览器回归说明](../tests/e2e/README.md)和[压测运行说明](../tests/k6/README.md)。
