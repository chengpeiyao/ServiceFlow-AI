# ServiceFlow AI S1 实施与验证记录

日期：2026 年 10 月 19 日  
设计基线：原 V0.3.1 开发契约，现已整合为 [开发需求规格说明书](../design/ServiceFlow_AI_Requirements.md)。\
阶段结论：S1 最小 Java 业务纵切已实现，真实 HTTP/MySQL 集成测试通过。S1-AI 真实模型实验及 S2/S3 尚未执行。

## 修改内容

新增独立 Spring Boot 后端，包括登录、当前用户、退出、合同权益检查、工单创建/查询、SLA 初始快照、客户补充、人工受理、资源授权、幂等记录和操作审计。

- 合同范围、有效期、启用状态和服务权益由 Java 校验；路由与 SLA 版本缺配置时拒绝创建。
- 客户归属、主管团队范围和工程师当前任务范围在服务端检查。客服按 P0 契约拥有内部全工单受理权限。
- 客户原始描述不变，产品申报只允许客户在 `SUBMITTED` 阶段显式更新；人工确认结果独立保存。
- 消息与受理先锁工单，再检查当前主体、版本和业务条件。写入、审计、SLA 事件及幂等结果共用数据库事务。
- 幂等摘要包括动作、资源路径和规范化 JSON，避免不同工单错误复用响应。重放成功请求不重复受理，也不重新触发版本变更。
- 明确发送的真人公开答复记录首次响应；内部备注、客户补充和受理确认不计入。完整解决时钟仍在后续阶段实现。
- 人工确认事实检查原文引用；诊断知识检查审核状态、产品及精确版本适用性。内容是否支持诊断仍需要人工审核和后续模型实验。
- `proposalId` 非空时明确拒绝，未把 AI 草稿接入伪装成已实现功能。

原始 V0.3.1 SQL 按字节复制为 Flyway `V1__serviceflow_p0_core.sql`，没有重新设计或覆盖原始 SQL。独立的 `db/demo/V2__demo_seed.sql` 只在 `dev` 或测试配置下加载，包含 2 家客户、有效/过期/停用合同、2 类服务与团队、策略版本及假账号。

原设计文档仅修复 V0.3 历史文件链接；原验收清单更新了 S1 结果，并将 S1-10 改为两种先后顺序及并发竞争，避免假定固定胜方。

## 环境与版本

| 项目 | 本次实际使用 |
| --- | --- |
| JDK | Microsoft OpenJDK 21.0.12.1；签名检查为 Valid |
| Maven | 3.9.11 |
| Spring Boot | 3.5.16 |
| MyBatis-Plus | 3.5.17 |
| Sa-Token | 1.44.0 |
| Flyway | 11.7.2，由 Spring Boot BOM 管理 |
| MySQL Server | 8.0.46，独立临时实例 |
| MySQL Connector/J | 9.7.0，由 Spring Boot BOM 管理 |
| 测试入口 | 随机端口 HTTP 服务、真实 MySQL、JUnit 5 |

本机默认 JDK 17，不修改全局配置；本轮使用工作目录内的便携 JDK 21。Docker 未安装，因此没有执行 Testcontainers 容器启动分支；相同测试通过外部测试 URL 接入隔离的真实 MySQL。仓库默认保留 Testcontainers 入口，并提供无需 Docker 的 Windows 临时 MySQL 测试脚本。

## 检查结果

首次 `mvn test` 执行 27 项集成测试，失败 0、错误 0、跳过 0。随后通过交付脚本重新创建临时实例并执行 `mvn clean verify`，最终同样是 27 项通过，完成可执行 JAR 打包。

最终 Surefire 记录：27 项，失败 0、错误 0、跳过 0；测试类耗时约 11.017 秒。该时长仅为本次测试运行时间，不是业务性能或压测结果。

| 验收范围 | 实际检查 |
| --- | --- |
| S1-01 | 合法创建，团队、优先级、SLA 版本及原文正确保存 |
| S1-02 | 跨客户合同拒绝，无工单/SLA/成功审计残留 |
| S1-03 | 合同过期、停用、服务未覆盖拒绝 |
| S1-04 | 策略切换后，新旧工单各自保留对应版本 |
| S1-05 | 跨客户读取工单和 SLA 被拒绝，错误体无敏感内容 |
| S1-06 | 产品及版本可空 |
| S1-07 | 客户补充更新申报信息、保存消息、递增版本并审计 |
| S1-08 | 人工受理保持冻结字段及原文不变，不自动停止首响 |
| S1-09 | 非法或未知受理字段返回 422，工单不变 |
| S1-10A | 补充先提交，旧版本受理返回 409 |
| S1-10B | 受理先提交，旧版本补充返回 409 |
| S1-10C | 双 HTTP 请求同时竞争，只允许一个旧版本写入成功 |
| 认证与资源权限 | 登录失败、未登录、退出、账号停用、客户冒充、角色与团队范围 |
| 消息与首响 | 内部备注不对客户展示；真人公开答复才停止首响 |
| 幂等 | 创建/受理重放、同键不同正文或资源、双请求同键创建 |
| 原子性 | MySQL 触发器注入审计失败，验证工单、SLA、事件及幂等记录回滚 |
| 产品来源 | 未知代码拒绝、仅报版本合法、切换产品不继承旧版本、申报修改边界 |
| 证据校验 | 无来源事实拒绝、知识精确版本匹配、未审核/产品不适用知识拒绝 |

并发测试用两条真实 HTTP 请求，由不同服务线程和数据库事务执行。通过屏障同步请求发起时点，不假定哪一方先取得工单锁；另外独立验证两种确定的提交顺序。没有使用 Mock Mapper 替代数据库并发验证。

审计失败探针会在测试日志中产生预期的 SQL 异常；测试断言是该请求返回受控 500、整笔事务回滚。这不代表集成测试失败。

## 复现方式与证据

在 `backend` 下、JDK 21 环境运行：

```powershell
.\scripts\Test-LocalMySql.ps1 -JavaHome '你的 JDK 21 目录'
```

脚本创建独立临时 MySQL，执行 `mvn clean verify`，然后关闭实例、验证临时目录边界并清理数据。不会连接既有业务库。结果见：

- `backend/target/surefire-reports/com.serviceflow.S1IntegrationTest.txt`
- `backend/target/surefire-reports/TEST-com.serviceflow.S1IntegrationTest.xml`
- `backend/target/local-mysql-init.log`
- `backend/target/local-mysql-server.log`
- `backend/target/serviceflow-backend-0.1.0-SNAPSHOT.jar`

构建产物和运行日志在 `.gitignore` 中排除。重新构建会替换 `target`，需要长期保存的结果应归档。

环境准备过程中，默认代理连接失败，后改用获准的官方下载路径获取 JDK；本地数据库目录核验首次遇到 MySQL 批处理反斜杠转义问题，修正为原始输出和规范化路径比较后，隔离测试脚本已完整通过。上述环境处理没有更改全局 Java 或代理配置。

## 风险与后续事项

- 当前会话为单进程内存态，应用重启后重新登录。
- 演示账号与公开口令仅在开发/测试配置使用，默认配置不导入种子数据。
- 真实模型 A0/A1/A2 实验、AI 生成/草稿确认、转派、验收与完整 SLA 算法没有实施，不能据此宣称这些阶段通过。
- 没有前端页面，本阶段通过真实 HTTP 和数据库完成验证。
- 尚未验证 Docker/Testcontainers 分支，不把外部 MySQL 测试结果当成该分支已通过。
- S1 完成后可按计划开展 S1-AI；模型效果与成本需要独立的真实实验记录。

## 影响文件

新增根目录 `README.md`；新增 `backend/pom.xml`、`.gitignore`、`scripts/Test-LocalMySql.ps1`；新增应用配置、Flyway 初始迁移及开发种子。

Java 主代码包括 `ServiceFlowApplication`，`shared` 下的异常与 Web 配置，`identity` 下的认证服务/接口/Mapper，`contract` 下的权益服务/Mapper，`ticket` 下的请求契约/服务/接口/Mapper，`sla/SlaMapper`，以及 `audit` 下的审计、幂等服务和 Mapper。

新增测试 `S1IntegrationTest.java`、`MySqlTestSupport.java`。文档修改仅为 `ServiceFlow_AI_Design_V0.3.1.md` 的历史链接、`ServiceFlow_AI_S1_AI_Validation_V0.3.1.md` 的结果与并发用例说明，以及本实施记录。
