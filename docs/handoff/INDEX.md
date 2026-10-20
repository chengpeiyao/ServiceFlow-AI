# 项目交接导航：ServiceFlow AI

新会话先读 [项目约束](../../AGENTS.md) 与 [当前状态和下一步](CURRENT.md)，再按任务打开下表中的入口。

## 项目判断与交接范围

目标是企业软件服务履约与 AI 辅助故障受理。当前已实现 S1 基础业务、S2 任务执行及验收整改后端闭环，AI 为独立实验；完整产品目标见 [开发需求规格说明书](../design/ServiceFlow_AI_Requirements.md)，已交付边界见当前状态。

采用裁剪后的 **`standard`** 档位，只新增项目约束、本索引和当前状态三份入口：

- 规模：单 Maven 后端，身份、合同、工单、任务、SLA、审计和实验按包组织；没有前端或独立部署的多服务。
- 技术：Java 21 + Spring Boot + MyBatis-Plus + Sa-Token + Flyway + MySQL；独立实验复用同一 Java 工程，具体版本见 [pom.xml](../../backend/pom.xml)。
- 复杂度：权限范围、任务转派、并发写入、幂等、审计回滚和 SLA 具有业务风险，值得保留明确约束和真实数据库测试。
- 裁剪依据：统一需求规格、S2 实施说明与验证记录已承载关键契约，直接复用，暂不新增架构总表、验证手册或模块模板。协作人数和部署环境尚未核实，不作为扩大流程的依据。

## 按任务定位

代码路径均相对仓库根目录。

| 任务 | 代码入口 | 需求、实施或验证入口 |
| --- | --- | --- |
| 启动、配置、测试 | [应用入口](../../backend/src/main/java/com/serviceflow/ServiceFlowApplication.java)、[应用配置](../../backend/src/main/resources/application.yml)、[统一测试脚本](../../scripts/Test-Project.ps1) | [根 README](../../README.md)、[目录指南](../Project_Directory_Guide.md) |
| 登录、资源权限、合同权益 | `identity/IdentityService.java`、`contract/ContractService.java`、`ticket/TicketService.java`，均在 `backend/src/main/java/com/serviceflow` 下 | [S1 实施记录](../implementation/ServiceFlow_AI_S1_Implementation.md)、[S1 集成测试](../../backend/src/test/java/com/serviceflow/S1IntegrationTest.java) |
| 工单提交、补充、人工受理、SLA 首响 | [TicketService](../../backend/src/main/java/com/serviceflow/ticket/TicketService.java)、[SlaMapper](../../backend/src/main/java/com/serviceflow/sla/SlaMapper.java) | 需求规格第 4、7、8 节、S1 实施记录及 S1 集成测试 |
| 工单主动取消、工单分页 | [TicketController](../../backend/src/main/java/com/serviceflow/ticket/TicketController.java)、[TicketMapper](../../backend/src/main/java/com/serviceflow/ticket/TicketMapper.java)、[SlaService](../../backend/src/main/java/com/serviceflow/sla/SlaService.java) | 需求规格第 4.5、7、11.2 节、[接口测试](../../backend/src/test/java/com/serviceflow/TicketOperationsIntegrationTest.java)、[验证汇总](../verification/ticket-operations-2026-10-20/summary.json) |
| 任务创建、派工、转派及处理 | [TaskService](../../backend/src/main/java/com/serviceflow/task/TaskService.java)、[TaskController](../../backend/src/main/java/com/serviceflow/task/TaskController.java) | [S2 首段契约与实现](../implementation/ServiceFlow_AI_S2_First_Segment_2026-10-20.md)、[S2 集成测试](../../backend/src/test/java/com/serviceflow/S2IntegrationTest.java) |
| 方案、创建者验收、整改与解决计时 | [ResolutionService](../../backend/src/main/java/com/serviceflow/ticket/ResolutionService.java)、[SlaService](../../backend/src/main/java/com/serviceflow/sla/SlaService.java)、[V4 迁移](../../backend/src/main/resources/db/migration/V4__resolution_cycles_and_sla_precision.sql) | 需求规格第 6、7、11 节、[闭环测试](../../backend/src/test/java/com/serviceflow/ResolutionIntegrationTest.java)、[本段验证](../verification/s2-resolution-2026-10-20/summary.json) |
| 幂等、审计、数据库约束 | [IdempotencyService](../../backend/src/main/java/com/serviceflow/audit/IdempotencyService.java)、[Flyway 初始迁移](../../backend/src/main/resources/db/migration/V1__serviceflow_p0_core.sql) | 需求规格第 3、10、13、15 节、S1/S2 并发与回滚用例；[需求与 SQL 导航](../design/README.md) |
| AI 实验、复核、复现 | [ExperimentCli](../../backend/src/main/java/com/serviceflow/experiment/ExperimentCli.java)、[HybridTriage](../../backend/src/main/java/com/serviceflow/experiment/HybridTriage.java) | [实验说明](../../experiments/s1-ai/README.md)、[人工复核入口](../../experiments/s1-ai/review/README.md)、[证据评分说明](../../experiments/s1-ai/review/Evidence_Review_Guide.md)、[A3 冻结](../../experiments/s1-ai/freezes/a3-v01-2026-10-20/README.md) |

## 信息维护位置

- 开发行为和业务约束：根目录 `AGENTS.md`；当前任务、验证范围、未完成事项：`CURRENT.md`；入口和链接：本页。
- 需求契约：[统一规格书](../design/ServiceFlow_AI_Requirements.md)；SQL 参考：[需求与 SQL 导航](../design/README.md)；已交付说明：[实施导航](../implementation/README.md)；历史结果：[验证导航](../verification/README.md)。后续直接维护统一规格，不新建并行版本；需求不能替代源码和验证记录。
- 历史证据旧路径：[目录迁移映射](../verification/workspace-layout-2026-10-20/relocations.json)。不为修正旧路径改写原始 manifest 或冻结快照。

后续仅维护发生实质变化的信息。当前导航足以支持任务时不扩展文档体系；出现新的跨模块契约或反复丢失的上下文时，再补充相关说明。
