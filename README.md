# ServiceFlow AI

本仓库的统一开发基线为 [开发需求规格说明书](./docs/design/ServiceFlow_AI_Requirements.md)。当前 Java API 后端已支持登录、合同权益、工单受理、任务派工与执行，以及解决方案提交、创建者验收、拒绝整改、工单主动取消、工单分页和 SLA 双时钟。任务列表、候选项等辅助查询和 Vue3 页面待后续实现；AI 仍为独立实验，A3 已冻结，人工语义效果尚未验收。

## 技术与目录

- JDK 21、Maven 3.9.x。
- Spring Boot 3.5.16、MyBatis-Plus 3.5.17、Sa-Token 1.44.0。
- Flyway 11.7.2、MySQL 8.0.46；具体传递依赖由 Spring Boot BOM 管理。
- `backend/src/main/resources/db/migration`：25 表初始迁移及后续增量；V4 增加任务轮次、整改来源和 SLA 累计毫秒。
- `backend/src/main/resources/db/demo`：仅在 `dev` 和测试环境加载的假账号、合同、路由、SLA 及知识样例。
- `backend/src/test`：真实 HTTP/MySQL 集成测试。
- `docs/design`、`docs/implementation`、`docs/verification`：设计、实施与验证材料，统一由文档导航索引。
- `scripts/Test-Project.ps1`：统一测试入口，使用项目内 JDK、Maven 缓存和临时目录。
- `tools`：本机 JDK 与 Maven 依赖缓存，供离线开发使用；大型本地文件不纳入 Git。
- `docs/verification/local-history/development`：原样保留的验证日志、检查结果及变更前备份。
- `experiments/s1-ai/freezes`：A3 冻结快照及原运行 JAR；正式原始输出在 `experiments/s1-ai/evidence`。
- `artifacts/runtime`：当前 Ollama 服务日志；`backend/.local/tmp`：Java 测试临时文件。

完整说明见 [项目目录与清理规则](./docs/Project_Directory_Guide.md)，设计和验收文件见 [文档索引](./docs/README.md)。

AI 开发从 [项目约束](./AGENTS.md) 和 [交接索引](./docs/handoff/INDEX.md) 开始；当前接续状态见 [CURRENT](./docs/handoff/CURRENT.md)，后续按实质变化维护。

推荐在项目根目录运行：

```powershell
.\scripts\Test-Project.ps1 -Offline
```

该入口调用现有独立 MySQL 测试脚本，默认使用 `tools/java/jdk-21.0.12.1+1`、`tools/maven/repository` 和 `backend/.local/tmp`；要求 PATH 中已有 Maven 与 MySQL 程序。去掉 `-Offline` 可在需要时下载依赖。脚本使用带 BOM 的 UTF-8 以兼容 Windows PowerShell 5.1；原生命令警告保留在日志中，成败依据退出码判断。

构建产物位于 `backend/target`；启动或运行新模型实验前，应确认产物与当前源码一致，必要时执行上述命令重新构建。90 项历史测试及源码摘要保存在 [S2 验证记录](./docs/verification/s2-first-2026-10-20/summary.json)。

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

`dev` 配置会导入演示账号，统一演示口令为 `S1Demo!2026`。账号有 `customer_a`、`customer_b`、`support`、`manager`、`manager_other`、`engineer`、`ops`，S2 演示增加 `engineer_backup`（数据库团队）、`engineer_other`（应用团队）和 `manager_backup`（数据库团队主管）。BCrypt 摘要存在演示迁移中；此口令仅用于假数据演示。默认配置不导入这些账号，业务库与演示库分开使用。

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

## S2 首段接口

已支持任务创建、首次派工、转派、接单、开始、完成、未派任务取消及内部详情查询。写入携带工单/任务双版本，使用工单锁、请求幂等与数据库单活动任务约束；转派后新人必须重新接单，旧负责人失权。完整请求字段及状态限制见 [S2 首段说明](./docs/implementation/ServiceFlow_AI_S2_First_Segment_2026-10-20.md)。

2026-10-20 S2 首段的完整回归记录为 90 项：S1 27 项、原实验 32 项、A3 6 项、S2 25 项，包含真实 MySQL 并发与审计失败回滚，见 [S2 汇总](./docs/verification/s2-first-2026-10-20/summary.json)。随后目录整理回归通过 89 项，明确排除涉及固定检查集规则评测的 `ExperimentTest#allBaselineOutputsPassSharedValidation`，见 [整理汇总](./docs/verification/workspace-layout-2026-10-20/summary.json)。两份均为历史记录，不能视为新一轮验证。

## S2 验收整改接口

| 方法与路径 | 行为 |
| --- | --- |
| `POST /api/v1/tickets/{id}/resolutions` | 本轮已完成任务的当前工程师提交方案，工单进入待验收并暂停解决计时 |
| `POST /api/v1/resolutions/{id}/acceptance` | 创建者通过后关闭；拒绝后恢复计时并原子创建未派整改任务 |
| `GET /api/v1/tickets/{id}/resolutions` | 有权用户按轮次查看方案、验收及整改任务来源 |

提交正文为 `expectedTicketVersion/taskId/expectedTaskVersion/summary`；验收正文为 `expectedTicketVersion/decision/reason`，`decision` 为 `ACCEPT` 或 `REJECT`，拒绝必须说明原因。两类写入均携带 `Idempotency-Key`。整改任务复用既有派工、接单、开始和完成接口，派工时工单保持 `IN_PROGRESS`。完整协议见规格第 6、7、11 节。

已完成任务不直接关闭工单。等待验收只暂停解决时钟，公开人工答复仍可形成首响；多轮整改保留累计毫秒，关闭时结算冻结的 SLA 目标。没有人工首响即关闭时，首响结果为不达标。

本段定向回归及构建结果见 [验证汇总](./docs/verification/s2-resolution-2026-10-20/summary.json)。复跑相同范围：

```powershell
$previousMavenArgs = $env:MAVEN_ARGS
try {
    $env:MAVEN_ARGS = '-Dtest=S1IntegrationTest,S2IntegrationTest,ResolutionIntegrationTest'
    .\scripts\Test-Project.ps1 -Offline
} finally { $env:MAVEN_ARGS = $previousMavenArgs }
```

该范围不运行 AI 实验套件、模型或固定集评测，不等于所有测试全量回归。

## 工单取消与分页

- `POST /api/v1/tickets/{id}/cancel`：创建者或客服取消 `SUBMITTED/TRIAGED` 且无活动任务的工单，携带 `Idempotency-Key` 和正文 `{"expectedTicketVersion":0,"reason":"客户撤回报障"}`。成功返回 `ticketId/status/ticketVersion/cancelledAt`；有活动任务返回 409，已派发或处理后的状态返回 422。
- `GET /api/v1/tickets?page=1&size=20&status=SUBMITTED`：按当前用户权限分页，返回 `items/total/page/size`。支持 `priority/customerId/ownerTeamId/keyword` 筛选；`keyword` 匹配标题。默认页大小 20、最大 100，固定创建时间与 ID 倒序，`total` 为十进制字符串。

取消停止解决计时并保留已有首响事实，取消原因对客户可见；不会取消或删除已有任务。列表的筛选始终与角色权限取交集，总数和条目使用同一读取快照。详细字段、错误码和可见范围见规格第 4.5、7、11.2 节。

这两个接口的定向回归见 [验证记录](./docs/verification/ticket-operations-2026-10-20/summary.json)。复跑时将上方 `MAVEN_ARGS` 设置为 `-Dtest=S1IntegrationTest,S2IntegrationTest,ResolutionIntegrationTest,TicketOperationsIntegrationTest`，执行统一入口后恢复原值。

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

会话暂存在单进程内存，重启后需要重新登录。人工服务主流程已具备后端验收整改闭环，以及工单主动取消和工单分页；任务列表、合同/服务类型/工程师候选项查询、前端页面及 AI 草稿业务接入尚未实现。V4 已在隔离空库随 Flyway 执行，已有业务数据的升级演练和生产部署未执行；本次两个接口未新增迁移。

## S1-AI 独立对照实验

已增加 A0 规则基线、A1 直接模型、A2 检索增强模型的独立 Java 实验入口，包含 20 条开发案例、40 条固定检查案例、24 条演示知识、统一校验与评测、人工判定表和费用估算记录。数据为合成资料，领域标注和真实模型效果仍需复核，不能把工具测试通过当作 AI 效果通过。

配置路径为 `experiments/s1-ai/config/model.local.properties`，密钥从其指定的环境变量读取；该目录不纳入 Git。运行步骤及指标边界见 [S1-AI 实验说明](./experiments/s1-ai/README.md)，实测状态见 [S1-AI 实施记录](./docs/implementation/ServiceFlow_AI_S1_AI_Implementation.md)。
