# 当前开发状态与交接：ServiceFlow AI

更新日期：2026-10-20。本页维护接续状态，代码入口见 [交接索引](INDEX.md)。

## 当前任务

- 本次按用户要求将 V0.2、V0.3、V0.3.1 设计整合为 [开发需求规格说明书](../design/ServiceFlow_AI_Requirements.md)，移除三份旧设计正文和旧设计包导航，保留 SQL 与原始证据。
- 统一规格覆盖业务、接口、页面、数据和验收，列明已实现与待开发边界；后续直接维护该文件。原文备份见规格第 16.3 节。本次未改应用代码、SQL、依赖或实验数据。
- 此前已建立项目约束与交接入口，继续按实质变化维护；用户尚未指定下一项功能实现。

## 源码和现有材料可证实的边界

- S1：登录、合同权益校验、工单创建与读取、客户补充、人工公开答复/内部备注、人工受理及 SLA 首响。源码见 `identity`、`contract`、`ticket`、`sla` 包及 [S1 测试](../../backend/src/test/java/com/serviceflow/S1IntegrationTest.java)。
- S2 首段：创建任务、首次派工、转派、接单、开始、完成和未派任务取消；[TaskService](../../backend/src/main/java/com/serviceflow/task/TaskService.java) 保持工单锁、双版本、幂等和当前权限校验。任务完成后工单仍为 `IN_PROGRESS`，不代表业务闭环结束。
- 尚未交付：解决方案提交、客户验收与拒绝整改、解决 SLA 暂停/恢复、Vue3 页面及 AI 草稿业务接入。会话目前为单进程内存，部署与持久化会话方案未在本次核实。
- AI：A0 为规则基线，A1/A2/A3 为独立实验；A3 已有 [冻结快照与原 JAR 说明](../../experiments/s1-ai/freezes/a3-v01-2026-10-20/README.md)。业务受理对非空 `proposalId` 仍拒绝，不能把模型候选当作业务能力已上线。
- 人工复核：现有 [复核材料](../../experiments/s1-ai/review/README.md) 与 [S2 实施记录](../implementation/ServiceFlow_AI_S2_First_Segment_2026-10-20.md) 表明标签/语义评审未完成，30 条 A2 诊断建议仍未评分；本次没有代填人工结论。

## 验证证据与限制

| 来源 | 记录结果 | 使用限制 |
| --- | --- | --- |
| [S2 首段历史汇总](../verification/s2-first-2026-10-20/summary.json) | 2026-10-20，90 项，失败/错误/跳过均为 0 | 当时的完整回归；源码摘要含整理前路径，通过迁移映射定位 |
| [随后目录整理汇总](../verification/workspace-layout-2026-10-20/summary.json) | 2026-10-20，89 项，失败/错误/跳过均为 0，JAR 构建成功 | 排除 `ExperimentTest#allBaselineOutputsPassSharedValidation`，该项涉及固定集规则评测；不是 90 项全量复跑 |
| 此前规范与交接初始化 | 75 个本地链接有效，无模板占位或行尾空白；历史 8 份 JUnit XML 与两份汇总一致，`git diff --check` 通过 | 历史文档检查，不是本次后端测试 |
| 本次设计整合 | 28 份 Markdown 的 179 个本地链接/锚点有效；规格覆盖 16 章与 25 表；4 份 SQL 摘要未变、4 份原文备份摘要一致，旧入口已切换；`git diff --check` 通过 | 仅文档验证，未重新运行 Java 构建/测试、模型或固定集评测 |

运行前提见 [根 README](../../README.md) 和 `scripts/Test-Project.ps1`：本机 JDK、Maven settings/缓存、Maven/MySQL 程序，以及隔离的测试实例。本次观察到 `backend/target` 已存在 JAR 和测试报告，但未重新构建或验证其与当前源码的一致性；启动或新实验前应按需重建，本次未验证工具能否启动。

## 推荐的接续工作

1. Java 后续段：从 [S2 首段说明的下一步](../implementation/ServiceFlow_AI_S2_First_Segment_2026-10-20.md#下一步) 和统一规格第 5～7、10、15、16 节开始，先明确整改任务来源、首次派工条件、创建者验收授权和 SLA 双时钟，再实现解决方案/验收/整改及跨命令并发验证。
2. AI 方向：从人工复核入口继续标签口径与诊断证据评分，保留 A3 冻结版本。口径、Prompt、规则和模型配置冻结后再评测固定集；本次没有启动该评测。

对应模块开发前需要固定的细节集中在统一规格第 16.2 节；后续按任务核实环境与验收范围，不为不相关的未知项暂停工作。
