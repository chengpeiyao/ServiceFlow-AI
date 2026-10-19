# ServiceFlow AI S1

本仓库按 V0.3.1 开发契约实现 S1：登录、合同权益检查、工单创建与查询、客户补充说明、人工受理、资源权限和请求幂等。当前交付是 Java API 后端；AI 运行与草稿确认、派工转派、客户验收、解决 SLA 暂停恢复和 Vue3 页面属于后续阶段。

## 技术与目录

- JDK 21、Maven 3.9.x。
- Spring Boot 3.5.16、MyBatis-Plus 3.5.17、Sa-Token 1.44.0。
- Flyway 11.7.2、MySQL 8.0.46；具体传递依赖由 Spring Boot BOM 管理。
- `backend/src/main/resources/db/migration`：基于验收的 V0.3.1 完整 25 表初始迁移。
- `backend/src/main/resources/db/demo`：仅在 `dev` 和测试环境加载的假账号、合同、路由、SLA 及知识样例。
- `backend/src/test`：真实 HTTP/MySQL 集成测试。
- `docs`：原设计、验收清单及 S1 实施记录。

## 本地启动

准备一个新的独立 MySQL 数据库 `serviceflow`，不要把初始迁移指向已手工导入 V0.3/V0.3.1 的库。Flyway 会在应用启动时建立结构。已手工建库的环境需另行确认基线，项目不会自动跳过迁移验证。

在 PowerShell 中设置当前进程环境，账号口令使用本机独立开发库的值：

```powershell
$env:JAVA_HOME = '你的 JDK 21 目录'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:SERVICEFLOW_DB_URL = 'jdbc:mysql://127.0.0.1:3306/serviceflow?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
$env:SERVICEFLOW_DB_USERNAME = '你的开发数据库账号'
$env:SERVICEFLOW_DB_PASSWORD = '你的开发数据库口令'
Set-Location .\backend
mvn spring-boot:run '-Dspring-boot.run.profiles=dev'
```

`dev` 配置会导入演示账号，统一演示口令为 `S1Demo!2026`。账号有 `customer_a`、`customer_b`、`support`、`manager`、`manager_other`、`engineer`、`ops`。BCrypt 摘要存在演示迁移中；此口令仅用于假数据演示。默认配置不导入这些账号，业务库与演示库分开使用。

登录示例：

```powershell
$login = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/auth/login' -ContentType 'application/json' -Body (@{ username='customer_a'; password='S1Demo!2026' } | ConvertTo-Json)
$headers = @{ Authorization="Bearer $($login.token)"; 'Idempotency-Key'=[guid]::NewGuid().ToString() }
$body = @{ customerId=1; contractId=1; title='数据库连接故障'; description='升级后提示数据库连接超时'; serviceType='DATABASE_SUPPORT'; impact='HIGH'; urgency='HIGH' } | ConvertTo-Json
$ticket = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/tickets' -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/tickets/$($ticket.ticketId)" -Headers @{ Authorization="Bearer $($login.token)" }
```

所有长整型 ID 在 JSON 中按字符串返回，避免浏览器丢失整数精度；版本号按整数返回。

## S1 接口

| 方法与路径 | 行为 |
| --- | --- |
| `POST /api/v1/auth/login` | BCrypt 校验后建立服务端会话 |
| `GET /api/v1/auth/me` | 返回当前有效用户与角色 |
| `POST /api/v1/auth/logout` | 注销当前会话 |
| `POST /api/v1/tickets` | 检查合同、客户归属、服务权益、路由和 SLA，再原子创建工单 |
| `GET /api/v1/tickets/{id}` | 按客户、团队或当前任务授权返回详情及活动 |
| `GET /api/v1/tickets/{id}/sla` | 查看创建时冻结的 SLA 投影 |
| `POST /api/v1/tickets/{id}/messages` | 追加客户消息或人工公开答复/内部备注 |
| `POST /api/v1/tickets/{id}/triage` | 仅客服人工受理，状态从 `SUBMITTED` 进入 `TRIAGED` |

业务写请求必须携带 `Idempotency-Key`。消息和受理正文必须携带 `expectedTicketVersion`。相同请求键、同一资源和相同内容返回原成功结果；复用键但更换内容或资源返回 409。读取时不泄露跨客户工单；客户端隐藏按钮不承担鉴权职责。

消息正文示例：

```json
{
  "expectedTicketVersion": 0,
  "content": "补充：产品 COMS，版本 11.2",
  "reportedProductCode": "COMS",
  "reportedProductVersion": "11.2"
}
```

产品代码目录为 `COMS,IOT`，可通过 `serviceflow.products` 配置。只有客户能在 `SUBMITTED` 阶段更新申报字段；省略或 `null` 表示不修改，空字符串表示清空。更换产品但没有提供版本时清空旧版本，避免跨产品继承。原始描述永远不被消息或受理覆盖。

人工受理正文示例：

```json
{
  "expectedTicketVersion": 1,
  "proposalId": null,
  "confirmedCategory": "DATABASE_CONNECTION",
  "confirmedFacts": [{"field":"symptom","value":"连接超时","sourceQuote":"提示数据库连接超时"}],
  "confirmedFollowups": [{"field":"errorLog","question":"请提供对应时间段的错误日志"}],
  "confirmedDiagnostics": [],
  "humanNotes": "人工补充说明"
}
```

`sourceMessageId` 为空时引文必须来自原始描述；引用补充消息时填写它的 ID。事实对象存于 `confirmed_facts_json.facts`，人工备注存于同一 JSON 的 `humanNotes`，不会伪装成客户原话。`category` 使用设计中的六类故障枚举。

受理拒绝包含冻结业务字段的请求；受理与客户补充竞争时，仅允许符合最新版本的写入成功。人工受理和追问草稿不自动计为首次响应；客服/当前工程师明确发送公开答复才记录首次响应，内部备注不计入。

`proposalId` 非空时当前阶段明确返回 `AI_TRIAGE_NOT_READY`，不会伪造 AI 草稿执行。人工诊断引用只检查已审核知识、产品及精确版本适用性；引文是否真正支持结论仍需人工核验和后续 AI 评测。

## 运行测试

默认测试使用 Testcontainers 启动 MySQL，需要 Docker：

```powershell
Set-Location .\backend
mvn clean verify
```

没有 Docker、但安装了 MySQL Server 二进制时，可由脚本启动隔离的临时实例，执行构建与测试后关闭并清理：

```powershell
Set-Location .\backend
.\scripts\Test-LocalMySql.ps1 -JavaHome '你的 JDK 21 目录'
```

脚本默认使用本机 `mysql.exe` 同目录的服务器程序，监听 `127.0.0.1:33319`；可通过 `-MySqlBin`、`-Port` 调整。它不会连接已有业务数据库，退出后保留 `target/local-mysql-*.log` 与 Surefire 报告。

也可显式指定已准备好的独立本地测试库。测试会清空业务记录，因此外部测试 URL 仅接受 `127.0.0.1/localhost`、显式端口及固定库名 `serviceflow_s1_test`：

```powershell
$env:SERVICEFLOW_TEST_DB_URL = 'jdbc:mysql://127.0.0.1:33319/serviceflow_s1_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
$env:SERVICEFLOW_TEST_DB_USERNAME = '独立测试数据库账号'
$env:SERVICEFLOW_TEST_DB_PASSWORD = '独立测试数据库口令'
mvn clean verify
```

测试使用真实 HTTP、MySQL 事务和连接池；不以 Mock Mapper 替代并发检查。测试期间的审计失败探针会刻意触发 SQL 错误，用于断言整笔事务回滚。

## 当前边界

会话暂存在单进程内存，重启后需要重新登录。S1 创建 SLA 实例并记录人工首响，不包含解决时钟暂停恢复和结算。当前没有前端页面、AI 模型调用或派工接口；后续阶段以独立测试结果验收，不把这些功能列为已完成。
