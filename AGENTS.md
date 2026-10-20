# AI 开发约束：ServiceFlow AI

先读 [交接索引](docs/handoff/INDEX.md) 和 [当前状态](docs/handoff/CURRENT.md)，再按任务读取源码及相关材料。本文只补充项目约束，沿用用户的全局规范。

## 项目与入口

- 企业软件服务履约与 AI 辅助故障受理项目；当前为单 Spring Boot 后端和独立 AI 实验，前端尚未实现。
- JDK 21、Maven 3.9.x、Spring Boot、MyBatis-Plus、Sa-Token、Flyway、MySQL；具体依赖以 `backend/pom.xml` 为准。
- `backend/src/main/java/com/serviceflow` 按业务包组织，沿用 Controller、Commands、Service、Mapper 风格。`experiments/s1-ai` 保存实验数据、配置模板和证据；[目录指南](docs/Project_Directory_Guide.md) 说明其他路径。
- P0 需求以 [开发需求规格说明书](docs/design/ServiceFlow_AI_Requirements.md) 为准；判断已实现行为时对照最新源码和测试，规格中的完整流程不代表全部已交付。后续直接维护该文件，不另建并行版本的设计正文。
- 保持模块化单体和现有依赖；不因初始化规范引入 Redis、MQ、流程引擎、向量数据库或额外框架。

## 业务修改必须保持的边界

- 工单聚合写操作遵守需求规格第 10 节锁协议；已有工单先取工单行锁，涉及任务时沿用“工单 → 幂等记录 → 任务”的顺序，在锁内检查最新授权、状态和版本。
- 保留请求幂等、工单/任务双版本及数据库单活动阻塞任务约束；业务变更、活动、派工历史、审计与幂等结果同事务提交。并发验证使用真实 MySQL 多连接，不能用 Mock Mapper 代替。
- 后端负责客户隔离、主管团队范围和当前任务归属校验；幂等重放也检查当前权限。转派后新负责人重新接单，原负责人不能借旧请求继续操作。
- 合同、服务类型、负责团队、优先级、SLA 版本由 Java 创建时决策并冻结；原始客户描述不可被受理或 AI 覆盖。SLA 首响与解决时钟的语义以契约为准，任务完成不能直接等同于关闭工单。
- AI 保持独立实验边界；业务 `proposalId` 非空仍返回 `AI_TRIAGE_NOT_READY`，接入草稿写路径需要单独实现与验证。模型调用不进入持锁事务，不让模型决定权限、派工或 SLA。
- API 的长整型 ID 按字符串返回、版本按整数返回；沿用现有请求校验和错误码。

## 数据、配置与证据

- 数据库演进新增 Flyway 迁移，不修改已应用的迁移文件；演示数据只用于 `dev` 或隔离测试环境，不把初始迁移直接指向已有手工导入库。
- 测试会清空业务记录，只能使用脚本新建的临时实例或符合 `MySqlTestSupport` 限制的独立本地 `serviceflow_s1_test` 库。
- 不改写 `experiments/s1-ai/evidence`、既有 `freezes` 和历史验证 JSON/XML/日志/manifest。新实验另建运行目录和版本；历史旧路径通过已有迁移映射定位。
- 模型、Prompt、规则、标签或知识变更要保留版本和可比性。固定检查集在口径、Prompt、规则和模型配置冻结后才用于评测；工程检查通过不等于人工语义或 AI 效果验收通过。
- 本机配置和密钥不进入文档或 Git；沿用 `.gitignore`，不提交 `tools` 缓存、`backend/.local`、构建产物或实验运行临时目录。

## 验证与按需交接

- 根目录统一回归入口：`.\scripts\Test-Project.ps1 -Offline`。前提是项目内 JDK 21、Maven settings/缓存，以及 PATH 中的 Maven、MySQL 程序可用；启动方式和替代测试入口见 [README](README.md)。
- 按改动选取相关套件：`S1IntegrationTest`、`S2IntegrationTest`、`ResolutionIntegrationTest`、`TicketOperationsIntegrationTest`、`ExperimentTest`、`HybridTriageTest`。完整入口执行 `clean verify`，其中 `ExperimentTest#allBaselineOutputsPassSharedValidation` 会对固定集运行规则基线；任务要求不评测固定集时应明确排除并记录范围，不能把部分回归说成全量通过。
- 纯文档改动检查本地链接、事实一致性和 `git diff --check` 即可；当前未配置独立 lint 或 typecheck 命令。测试前提缺失时如实记录，不为初始化文档安装依赖。
- 目标、范围及验收标准用足够简短的说明表达，授权明确时直接执行。流程按风险扩展，不附加 COMS 阶段门禁、审批或模块七视角。
- 接续任务、阻塞项、下一步或有价值的验证状态改变时更新 `docs/handoff/CURRENT.md`；入口变化更新索引；契约、架构、运行方法变化更新原有对应文档。无持续影响的小修复无需更新交接包。
- 每类事实只维护一个主要位置，其余用链接；事实、预期、推测和未验证项明确区分。最终说明实际修改文件、检查结果及风险。
