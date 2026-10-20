# S2 首段：任务派工、转派与并发验证

日期：2026-10-20。已完成任务创建、首次派工、转派、接单、开始、完成以及未派任务取消，90 项自动化测试通过，其中 S2 新增 25 项。当前完成 S2 的任务执行段，解决方案提交、客户验收与整改、解决 SLA 暂停/恢复仍待后续实现，不能宣称整个 S2 闭环已完成。

## 实现与边界

新增 `com.serviceflow.task` 下的 Commands、Controller、Mapper、Service。沿用现有 25 张表，任务处理结果写入带 task_id 的 ticket_activity，历史派工写入 task_assignment_history，业务审计和幂等结果与双版本同事务提交。所有任务活动目前为内部记录；不会自动计为客服首次响应，也不自动关闭解决 SLA。

工单从 TRIAGED 首次派工到 ASSIGNED，工程师开始后到 IN_PROGRESS。任务流转为：

```text
CREATED → ASSIGNED → ACCEPTED → PROCESSING → COMPLETED
   └──→ CANCELLED
ASSIGNED / ACCEPTED / PROCESSING ──合法转派──→ ASSIGNED
```

转派保持工单状态，新的负责人必须重新接单、开始；原处理记录和派工历史全部保留。原负责人不能再操作任务、读取任务内部详情或使用 S1 工单消息接口写入。完成后的当前负责人仍可查看自己的任务结果；后续提交解决方案的授权随下一段一起实现。

本段首次派工仅处理首轮 TRIAGED/CREATED。整改任务的内部创建及 IN_PROGRESS 下首次派工，须在客户拒绝验收原子事务实现后补齐。P0 不允许取消已派发任务，也不开放任意 IN_PROGRESS 建任务或跨团队变更。

## 接口

所有路径以 `/api/v1` 开头。写请求必须带有效登录及 `Idempotency-Key`，任务写请求同时携带 expectedTicketVersion 和 expectedTaskVersion。创建只带工单版本。

| 方法与路径 | 权限 | 正文中的其他字段 |
|---|---|---|
| POST /tickets/{ticketId}/tasks | 工单负责团队主管 | title |
| GET /tasks/{id} | 有团队范围的主管、当前负责人、客服、运营管理员 | 无；返回 task、assignments、activities |
| POST /tasks/{id}/assignments | 工单负责团队主管 | assigneeId、reason |
| POST /tasks/{id}/reassignments | 工单负责团队主管 | expectedAssigneeId、assigneeId、reason |
| POST /tasks/{id}/accept | 当前工程师 | 无 |
| POST /tasks/{id}/start | 当前工程师 | 无 |
| POST /tasks/{id}/complete | 当前工程师 | result |
| POST /tasks/{id}/cancel | 工单负责团队主管 | reason |

创建返回 201，其余成功写入返回 200。写入响应包含 ticketId、taskId、ticketStatus、taskStatus、ticketVersion、taskVersion、currentAssigneeId。ID 仍以字符串返回，版本仍为整数。需要刷新时，从工单详情获取工单版本，从任务详情获取任务版本；两个读取之间如发生变化，写入会返回版本冲突。

例如，工单人工受理后版本为 1，创建任务：

```json
{"expectedTicketVersion":1,"title":"数据库连接池诊断"}
```

创建成功后工单版本为 2、任务版本为 0；首次派工正文：

```json
{"expectedTicketVersion":2,"expectedTaskVersion":0,"assigneeId":"5","reason":"数据库团队值班派工"}
```

首次派工成功后工单版本为 3、任务版本为 1；转给备用工程师：

```json
{"expectedTicketVersion":3,"expectedTaskVersion":1,"expectedAssigneeId":"5","assigneeId":"8","reason":"值班交接"}
```

这些版本仅对应示例连续操作；实际调用必须使用最新响应/读取版本。新负责人接单、开始或完成也要使用更新后的两个版本。空结果、空原因、未知字段及非法版本拒绝。

主要拒绝：工单旧版本 `409 VERSION_CONFLICT`，任务旧版本 `409 TASK_VERSION_CONFLICT`，负责人不匹配 `409 ASSIGNEE_CONFLICT`，已有活动任务 `409 ACTIVE_TASK_EXISTS`，相同负责人转派 `422 SAME_ASSIGNEE`，无效工程师 `422 ASSIGNEE_NOT_ELIGIBLE`，非法状态 `422 INVALID_STATE_TRANSITION`。不同内容复用请求键仍返回 `409 IDEMPOTENCY_KEY_CONFLICT`。越权资源返回 404，错误角色写入返回 403。

## 事务与权限

任务 ID 只用于普通查询定位工单，不能作为锁后状态判断。写入使用 READ_COMMITTED，依次获取工单主键锁、幂等记录锁、任务行锁；在锁内检查最新状态、双版本、主管范围/当前负责人及候选工程师资格。候选工程师账号和团队成员关系使用共享锁，保证资格检查到提交期间不被并发撤销。

每次任务变更递增工单版本，已有任务变更同时递增任务版本；新任务从版本 0 开始。重复请求返回原成功响应，不追加历史或活动。重放仍核对当前授权：主管管理范围撤销、旧负责人被转出后均不能借旧请求键绕过权限。

MySQL 的 uk_one_active_blocking_task 继续作为第二道约束。事务中无模型调用或其他外部业务调用。审计失败会使任务、派工历史、工单版本、活动和幂等预留全部回滚。

## 验证

通过项目 D 盘 JDK、Maven 缓存及临时目录执行 `scripts/Test-Project.ps1 -Offline`，独立真实 MySQL 完成 clean verify：

| 套件 | 通过数 |
|---|---:|
| 原 S1 业务集成测试 | 27 |
| 原实验测试 | 32 |
| A3 工程测试 | 6 |
| S2 真实 HTTP/MySQL 测试 | 25 |
| 合计 | 90 |

失败、错误和跳过均为 0，JAR 构建成功。S1 原测试、V1/V2 迁移、AI 实验源码及四份数据/Schema 摘要未变。新增 V3 仅在 dev/测试配置下补演示用户，不修改既有迁移校验和，不新增表。

S2 测试覆盖完整任务生命周期、三种状态转派、新人重新接单、原负责人失权、团队/角色/停用账号限制、双版本、同人拒绝、终态和跳步拒绝、取消替换、内部数据隔离、幂等重放与撤权、唯一约束、审计故障回滚。

并发用例使用真实 HTTP 请求与 MySQL 事务，通过测试拦截器设置 SQL 入口栅栏或持锁暂停点，读取各事务的 CONNECTION_ID 并断言两条不同连接。覆盖两主管建任务、双人首次派工、同键重复派工、两次转派、转派先于旧负责人开始/完成、旧负责人合法操作先于转派，以及 S1 客户消息与 S2 任务写入竞争。拦截器仅协调时序，不返回假数据。

回滚测试中的 INTERNAL_ERROR 日志来自主动设置的 MySQL 审计失败触发器，HTTP 500 是该测试预期；触发器随后删除，同键重试成功。测试不是压力或性能测试，不据此作吞吐量承诺。

稳定验证资料：[汇总及源码摘要](../verification/s2-first-2026-10-20/summary.json)、[S2 JUnit XML](../verification/s2-first-2026-10-20/TEST-com.serviceflow.S2IntegrationTest.xml)。完整构建日志位于项目 docs/verification/local-history/development/s2-20261020/tests-first.log。

## A3 冻结与人工评测

A3 已生成 [冻结清单与快照](../../experiments/s1-ai/freezes/a3-v01-2026-10-20/README.md)。原运行 JAR 与既有环境记录 SHA-256 一致并单独保存，避免 S2 构建覆盖后失去复现实物。冻结采用文件快照与摘要，不自动提交现有未提交改动。

保留 A0 为确定性实验基线，A1/A2/A3 均不进入业务受理写路径。S1 的 AI_TRIAGE_NOT_READY 行为继续保留。本轮没有新增模型调用、没有调整 Prompt，也没有向模型发送固定检查集。

新增 [独立诊断证据评审说明](../../experiments/s1-ai/review/Evidence_Review_Guide.md) 与 20 个案例、30 条原始 A2 建议的成对底稿；每条同时展示客户原文、完整检索知识、版本/前置条件及 A0 规则建议。2 个整体输出被拒绝的案例也保留诊断材料，不能据此视为可用受理输出；1 个案例没有建议，单独记录而不从案例基数消失。

评分工具对引用支持、适用性、相对规则的新增价值、事实/推测混淆分别汇总；拒绝资料篡改、未知标识、不合法枚举和缺失审核人/理由。7 项工具检查通过。正式底稿 30 条仍全部未评分，工具自测的虚拟评分现保存于 docs/verification/local-history/development/s2-20261020/evidence-review-tool-tests，不是人工结论。标签和语义人工复核仍需实际审核人完成。

## 下一步

人工评分与 Java 开发可以分别推进。下一段 Java 工作是解决方案提交、创建者验收与拒绝整改、对应 SLA 暂停/恢复及跨命令并发。开始前应补齐整改任务的来源识别和首次派工条件，继续遵守工单锁协议。固定检查集继续保留到标签、任务定义与配置冻结后使用。
