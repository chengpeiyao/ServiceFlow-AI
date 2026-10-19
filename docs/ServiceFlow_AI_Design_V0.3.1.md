---
title: ServiceFlow AI V0.3.1 — P0 最小修订开发契约
aliases:
  - ServiceFlow AI V0.3.1
  - 企业服务履约系统开发契约
status: ready-for-s1-validation
document_version: v0.3.1
based_on: v0.3
created: 2026-10-19
tags:
  - Java后端
  - AI工程
  - 实习项目
  - 状态机
  - 事务并发
  - 业务规则
  - 模型评测
---

# ServiceFlow AI V0.3.1：P0 最小修订开发契约

> **定位：** 企业软件服务履约与 AI 辅助故障受理平台。Java 业务负责确定性权限、状态、合同、SLA 和派工；AI 负责从客户自然语言中提取信息、提出有来源的追问及排查建议，并经客服确认后落入业务系统。
>
> **来源：** 基于 [V0.3 开发契约](./ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_Design_V0.3.md) 按最新验收意见完成三项最小修订。关联 [V0.3.1 新库 SQL](./ServiceFlow_AI_P0_schema_V0.3.1.sql) 与 [V0.3→V0.3.1 结构增量](./ServiceFlow_AI_P0_patch_V0.3_to_V0.3.1.sql)。V0.2/V0.3 作为历史参考，**涉及 P0 冲突时以本版为准**。
>
> **验证状态（区分来源）：** 用户提供的独立验收报告称：原始 V0.3 SQL 在 MySQL 8.0.46 上 25 表建表成功、19 项数据/约束及回滚检查通过，双连接争抢创建任务仅保留一项活动任务。**这些是用户报告的 V0.3 检查结果，并非本次重新执行的实验。** V0.3.1 新增的可空产品字段与增量脚本尚未执行数据库复验；Java 服务、SLA 算法、权限、转派与 AI 对照实验均未通过运行验收。
>
> **开发约束：** Windows 本地开发、16GB 内存；单 Spring Boot 服务 + Vue3 + MySQL，云端模型 API；P0 不要求 Redis、MQ、Flowable、向量数据库及 GPU。

## 0. 本轮结论：三项小修订后进入验证

### 0.1 与 V0.2 的主要差异

| 主题 | V0.2 | V0.3 P0 决策 |
|---|---|---|
| AI 主任务 | 以派工推荐及审批为核心 | **故障事实提取 + 缺失信息追问 + 有证据的排查建议** |
| AI 写操作 | AI 派工审批，可能实际改派 | **只允许客服确认结构化受理草稿**；不改派工、合同、团队、优先级、SLA |
| 工单任务 | 可并行多个阻塞任务 | 同工单**至多一个活动阻塞任务**，历史已完成任务保留 |
| 合同异常 | 过期/不覆盖可人工特批 | **直接拒绝创建**，合同例外审批放到 P1 |
| SLA | 工作日历、客户等待暂停、验收等待暂停 | 固定 **7×24 双时钟**；无客户等待状态；仅解决时钟在验收等待期暂停 |
| AI 检索 | 知识/RAG 偏后期 | P0 采用审核知识的**关键词 + 元数据检索**，并验证引用能否支持结论 |
| 评测 | 约 30 条案例；缺少公平比较 | A0/A1/A2 + 独立固定检查集 + 成本、质量和安全分开评测 |
| 并发 | 主要覆盖派工 | **所有聚合写操作统一工单锁定协议** |
| 快照 | 保存建议前读取最新版本 | **绑定模型真实观察的版本**，生成后、确认时两次校验 |
| 权限 | 角色与成员关系，工单团队不明确 | 工单负责团队、人员归属、主管管理范围分别建模 |
| SLA 版本 | 历史策略可重复匹配 | 当前版本映射表唯一确定新工单策略；旧工单不受后续映射更新影响 |

### 0.2 本轮最小变更清单

| 本轮修正 | 明确契约 | 是否引入新业务模块 |
|---|---|---|
| 首次派工与转派拆分 | `assignTask` 只处理 `CREATED`；`reassignTask` 处理已派工的活动任务 | 否；沿用派工历史表 |
| 产品/版本未知时的知识检索 | 显式客户申报值优先；未知时仅用通用、版本无关知识并追问 | 否；仅增加工单两个可空申报字段 |
| AI 待审草稿替换 | 先判旧草稿版本失效，再判到期，最后判仍有效 | 否；沿用现有状态和唯一约束 |

### 0.3 P0 不做的事情

不做通用 CRM、SaaS 多租户计费、合同例外审批、跨团队转交、并行服务任务、客户等待暂停、节假日与工作日历、完整知识配置后台、向量数据库、复杂流程引擎、真实外部运维写操作、AI 自主派工和多 Agent 编排。

这些功能可在 P1/P2 扩展，但**不能反向影响 P0 的完成门槛**。

---

# 1. P0 产品对象、角色与业务来源

## 1.1 谁使用系统？

| 角色代码 | P0 可执行的行为 | 权限边界 |
|---|---|---|
| `CUSTOMER_CONTACT` | 提交本客户工单、补充说明、本人创建的工单验收 | 永远受 `customer_id` 限制 |
| `SUPPORT_AGENT` | 查看授权工单、发人工首次响应、审核 AI 受理草稿 | 不得派工、修改 SLA、团队或合同 |
| `SERVICE_MANAGER` | 对有管理权限的团队创建/替换未派任务、派工/转派 | 必须匹配工单 `owner_team_id` 与管理范围 |
| `ENGINEER` | 接单、开始处理、提交任务结果与解决方案 | 仅限当前授权任务，不得自己改变所属团队 |
| `OPS_ADMIN` | 通过迁移和种子数据维护演示配置，查看审计 | 不作为模型工具权限入口 |

P0 各用户只分配一个演示角色。**客服 `SUPPORT_AGENT` 在单服务商的 P0 中有内部全工单受理权限**，不额外建立客服团队范围；服务主管仍严格受 `team_manager_scope` 约束，工程师仍受任务归属约束，外部客户严格隔离。以后若需要客服按团队分工，再补客服资源范围表。多角色 RBAC 配置后台延后。

## 1.2 一条核心业务链

```text
客户提交故障描述 + 合同 + 服务类型 + 影响/紧急程度
    ↓ Java 校验合同权益并选定团队和 SLA 版本
SUBMITTED（存原文，不允许 AI 覆盖）
    ↓ 客服发起 AI 受理实验 / 或人工直接受理
AI 生成结构化候选 → 客服确认/编辑（Java 再校验版本）
    ↓ TRIAGED（只写确认分类与受理记录）
主管创建一项阻塞服务任务 → 派工
    ↓ ASSIGNED
工程师接单并开始处理
    ↓ IN_PROGRESS
工程师完成任务，提交解决方案
    ↓ PENDING_ACCEPTANCE（解决 SLA 暂停）
客户同意 → CLOSED
客户拒绝 → IN_PROGRESS + 原子创建一项整改任务
```

AI 不可用时，客服可以走同样的 `triage` 领域服务完成人工受理。**业务主流程不能依赖模型可用性**。

## 1.3 不变的确定性字段与 AI 可处理的内容

| 信息类型 | 示例 | P0 谁负责写入/变更 |
|---|---|---|
| 客户原文 | 原始故障描述 | 客户创建时一次写入，不可由受理覆盖 |
| 确定性业务字段 | `customer_id`, `contract_id`, `service_type`, `owner_team_id`, `priority`, `sla_policy_version_id` | Java 创建时决策/冻结；P0 不开放修改 |
| AI 候选事实 | 症状、影响现象、产品版本、发生条件 | 保存建议时带原文片段依据；客服审核后进入受理记录 |
| AI 建议 | 故障 `category`、追问列表、排查建议 | 可编辑、可拒绝；不作为确定性业务结论 |
| 人工修订 | 客服添加的信息和最终分类 | 单独保留人工确认记录，不能伪装成客户原话 |

**概念区分：** `service_type` 是合同权益/SLA/负责团队的业务服务类型；`category` 是故障诊断分类。AI 与客服确认 `category`，**不因此改变 `service_type`、团队或 SLA**。日后若要转服务类型，必须新增独立审批、重评规则及 SLA 迁移契约，而不是在受理接口暗中更新。

---

# 2. 状态机与“一个活动阻塞任务”不变量

## 2.1 工单状态（P0）

```text
SUBMITTED ──受理──> TRIAGED ──派工──> ASSIGNED
                                    │
                                 开始处理
                                    ↓
                               IN_PROGRESS
                                    │
                               提交解决方案
                                    ↓
                            PENDING_ACCEPTANCE
                               /            \
                         同意/              \拒绝（原子创建整改任务）
                            ↓                ↓
                          CLOSED         IN_PROGRESS

SUBMITTED 或 TRIAGED（尚无活动任务） ──合法取消──> CANCELLED
```

**不包含：** `ENTITLEMENT_REVIEW`、`WAITING_CUSTOMER`、独立 `REOPENED` 常驻状态。

| 命令 | 必须源状态 | 目的状态 | 关键约束 |
|---|---|---|---|
| `createTicket` | 无 | `SUBMITTED` | 合同/权益有效，路由规则与 SLA 策略唯一匹配 |
| `confirmTriage` | `SUBMITTED` | `TRIAGED` | 客服授权、版本匹配，只能确认分类和结构化受理字段 |
| `createTask` | `TRIAGED` 或验收拒绝后的 `IN_PROGRESS` | 不变 | **当前无其他活动任务**；P0 正常路径的 `IN_PROGRESS` 不开放随意建任务 |
| `assignTask`（首次派工） | `TRIAGED`，或 `IN_PROGRESS` 且当前是验收拒绝生成的整改任务 | 首轮 `TRIAGED → ASSIGNED`；整改仍 `IN_PROGRESS` | 任务必须 `CREATED` 且负责人为空；主管管理范围与新工程师资格有效 |
| `reassignTask`（转派） | `ASSIGNED` 或 `IN_PROGRESS`（首轮和整改均可） | **工单状态不变** | 任务必须 `ASSIGNED/ACCEPTED/PROCESSING`；当前负责人非空、新旧负责人不同；主管授权、版本与资格重新校验 |
| `acceptTask` | `ASSIGNED` 或 `IN_PROGRESS`（包括转派后） | 不变 | 新的当前负责人，任务 `ASSIGNED -> ACCEPTED`；旧负责人不得继续操作 |
| `startTask` | `ASSIGNED` 或 `IN_PROGRESS`（包括转派后） | `IN_PROGRESS` | 当前负责人，任务 `ACCEPTED -> PROCESSING`；已是 `IN_PROGRESS` 则保持 |
| `completeTask` | `IN_PROGRESS` | 不变 | 当前负责人，任务 `PROCESSING -> COMPLETED`，保留结果 |
| `submitResolution` | `IN_PROGRESS` | `PENDING_ACCEPTANCE` | **至少一项已完成阻塞任务且没有任何活动阻塞任务**；解决方案非空 |
| `acceptResolution` | `PENDING_ACCEPTANCE` | `CLOSED` | 仅创建者验收当前轮次，一轮一次 |
| `rejectResolution` | `PENDING_ACCEPTANCE` | `IN_PROGRESS` | 必填拒绝理由；原子地记拒绝+创建整改任务 |
| `cancelTask` | `TRIAGED` 且任务为 `CREATED` | `TRIAGED` | P0 仅支持取消尚未派发任务，然后可新建替代 |
| `cancelTicket` | `SUBMITTED` 或 `TRIAGED` | `CANCELLED` | 无活动任务（必要时先取消 `CREATED` 任务）；记取消原因 |

> **开发实现说明：** 首轮 `createTask` 仅从 `TRIAGED` 调用；客户拒绝时由 `rejectResolution` 内部原子创建整改任务，而不是独立对外放开任何 `IN_PROGRESS` 建任务接口。上表的 `IN_PROGRESS` 建任务仅用于描述该内部命令的业务结果。

### 2.1.1 首次派工与转派的精确事务契约

- **首次派工：** 任务 `CREATED → ASSIGNED`，此前 `current_assignee_id IS NULL`；创建派工历史记录（前负责人 `NULL`），设置当前负责人，任务与工单版本均加一。首轮工单由 `TRIAGED → ASSIGNED`；整改轮工单保持 `IN_PROGRESS`。
- **转派：** 仅针对任务 `ASSIGNED / ACCEPTED / PROCESSING`，且工单为 `ASSIGNED / IN_PROGRESS`。事务持有工单聚合锁，核对主管对 `owner_team_id` 的管理权限、候选工程师的团队资格、请求中的工单/任务期望版本和当前负责人。新旧负责人必须不同。
- **转派结果：** 保留原处理记录和历史派工；追加一条 `task_assignment_history`，更新 `current_assignee_id`，统一将任务置回 `ASSIGNED`，任务版本与工单版本均递增。工单维持原状态：`ASSIGNED` 时继续 `ASSIGNED`，`IN_PROGRESS` 时仍 `IN_PROGRESS`。
- **重新接单：** 新负责人必须再次 `acceptTask`（`ASSIGNED → ACCEPTED`），然后 `startTask`（`ACCEPTED → PROCESSING`）；转派前的 `ACCEPTED/PROCESSING` 不自动继承给新人。即使工单为 `IN_PROGRESS`，转派后的任务也可以暂处 `ASSIGNED`，表示该工单历史上已进入处理阶段、当前待新人接单。
- **旧工程师失权：** 事务提交后，旧工程师不能再接单、开始、完成或提交关联解决方案；服务端必须在**工单锁后**检查最新 `current_assignee_id`、任务版本与工单版本。失去资源权限返回 `403/404`；仍有权限但期望版本过期返回 `409`。不能凭前端隐藏按钮当权限控制。
- **并发与幂等：** 两个转派请求按工单聚合锁串行处理；旧期望版本的一方返回 `409`。重复 `Idempotency-Key` 只返回原执行结果，不新增派工历史。转给同一负责人返回业务拒绝，不制造无意义历史。
- **P0 仅允许“转派”，不支持已派任务任意取消。** 任务 `COMPLETED/CANCELLED`、工单 `PENDING_ACCEPTANCE/CLOSED/CANCELLED` 均不得转派。

## 2.2 活动阻塞任务的精确定义

- P0 所有 `service_task.is_blocking=1`，只有 `CREATED / ASSIGNED / ACCEPTED / PROCESSING` 算**活动任务**。
- `COMPLETED / CANCELLED` 不算活动任务，可长期保存多条历史。
- **任一工单活动阻塞任务总数 `<= 1`**。这是“至多一个”，不是必须永远有一个。
- `CREATED` 算活动。两个主管并发创建任务必须只有一个成功；失败方返回业务冲突。
- `TRIAGED` 且未派发任务被取消后，可以新建替代任务；`ASSIGNED/ACCEPTED/PROCESSING` 的取消放 P1，P0 若需要变更工程师则走合法转派。
- `PENDING_ACCEPTANCE / CLOSED / CANCELLED` 不能新增活动任务；客户拒绝验收是一笔将状态改回 `IN_PROGRESS` 并新建整改任务的**原子事务**。
- 历史 `COMPLETED` 任务不重复改回处理状态；拒绝验收新建另一项整改任务。

**两道保证：** Java 所有聚合写操作使用工单主键锁；MySQL 另用一个`generated column + UNIQUE`约束保护单活动任务。不依赖普通部分唯一索引。MySQL 唯一索引允许多个 `NULL`，已完成和取消的任务生成列为 `NULL`，因此历史记录不占用活动名额。对应 SQL 见 `uk_one_active_blocking_task`；用户报告 V0.3 已在 MySQL 8.0.46 验证双连接创建活动任务的约束。Java 聚合锁协议与转派等事务仍需另外实测。参考 [MySQL CREATE INDEX](https://dev.mysql.com/doc/refman/8.0/en/create-index.html)。

## 2.3 首轮和整改轮次

- 首轮和整改服务任务可按 2.1.1 合法转派多次，始终只有一项活动阻塞任务；每次转派都保留历史并重新要求新工程师接单。
- 解决方案提交时生成 `ticket_resolution` 的新 `cycle_no`，并设置工单 `resolution_cycle_no`。
- 验收拒绝在同一事务内完成：验收记录 `REJECT`、工单 `PENDING_ACCEPTANCE -> IN_PROGRESS`、SLA 继续计时、创建一项 `CREATED` 整改任务、版本递增和审计。
- 对同一解决方案的重复验收，通过唯一键 `resolution_id` 以及幂等记录避免重复生成整改任务。
- 首轮 `CLOSED` 后不可重开；关闭后重开留在 P1 业务评审。

---

# 3. 统一工单聚合锁与事务协议

## 3.1 为什么普通数据库事务不够？

`提交解决方案`和`新增活动任务`、`验收拒绝并新建整改任务`和`并发确认验收`都涉及工单级不变量。若仅各自开事务，不共享锁定顺序，就可能读取旧任务集合并做出互相矛盾的决定。

### 写命令统一流程

```text
先完成身份验证；必要时只读定位工单和校验资源权限
     ↓ 进入短数据库事务
SELECT service_ticket WHERE id=? FOR UPDATE
     ↓ 再次验证 actor 对最新 owner_team/customer 的权限
按一致顺序获取本次操作需要的幂等行、任务行（多行按 task.id 升序）
     ↓ 在锁内重新查询当前任务集合和工单状态
校验状态、不变量、请求版本和唯一约束
     ↓ 更新 ticket.version、任务、SLA 投影、结果、审计及幂等结果
COMMIT
```

**统一要求：** REST 写接口、AI 草稿确认接口、将来定时任务和后台内部命令都通过同一应用服务，不允许绕开聚合协调直接更新任务表。

- 不使用事务开始前的普通查询结果作为锁后判断依据。
- 当前 P0 不在事务锁内调用 LLM、检索服务、网络接口或长耗时计算。
- 修改工单任务集合、任务状态/负责人、客户输入、受理结果、解决方案、验收时，均递增 `service_ticket.version`。
- 同一事务涉及任务时按 ID 升序获取任务行锁；幂等行的获取顺序在各写入口统一，不混用不同顺序。
- 对死锁或锁超时仅在**无外部不可逆操作**时，有限次数重试整个事务；不能默认所有失败可重试。
- 仅有唯一键并不代替锁后状态验证；仅有锁也不代替唯一键兜底。

> 注意：InnoDB `SELECT ... FOR UPDATE` 的锁定行为和 `REPEATABLE READ` 快照读不同。需要真实 MySQL 多连接测试；参见 [MySQL 8.4 Locking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)。

## 3.2 必须覆盖的并发测试

| 用例 | 并发命令 | 必须保证 |
|---|---|---|
| `TX-01` | 两名主管创建同一工单首个任务 | 只存在一项活动阻塞任务 |
| `TX-02` | 创建任务与提交解决方案 | 不可能存在待验收且带活动阻塞任务的最终状态 |
| `TX-03` | 同一任务双人派工 | 当前负责人和派工历史一致；一方出现版本冲突或顺序生效 |
| `TX-04` | 同一轮次同意验收与拒绝验收 | 只能产生一种验收决策，不会在已关闭工单上生成整改任务 |
| `TX-05` | 受理确认与客户追加说明 | 旧版本受理草稿失效，原文和新增说明均保留 |
| `TX-06` | AI 草稿确认与主管/系统其他写操作 | 旧建议不得基于新业务状态直接落库 |
| `TX-07` | 同一幂等键重复提交 | 一次业务副作用，返回原操作结果；不同内容复用键返回冲突 |

测试必须使用**两个以上真实 MySQL 连接**并控制线程同步点，不能只依靠单线程或 Mock Mapper。另增 `TX-08`：转派与旧负责人开始/完成操作竞争，旧负责人无法在失去归属后完成业务写入；`TX-09`：两次 AI 生成间客户补充信息，旧待审草稿转 `STALE`，新观察版本的建议可成为唯一待审。

---

# 4. 合同、负责团队、SLA 策略的创建时决策

## 4.1 创建工单时的顺序

```text
客户必须属于合同的 customer_id
    ↓ 合同 ACTIVE 且 submitted_at ∈ [valid_from, valid_until)
合同必须对 service_type 授权
    ↓ service_type_routing 查到唯一负责团队 + routing_version
用户提交 impact + urgency → Java 固定矩阵确定 P1/P2/P3
    ↓ 根据 contract.service_tier + service_type + priority
sla_policy_binding 查唯一 active_policy_version_id
    ↓ 在同一事务保存工单 + SLA 快照 + 审计
```

若任一条件失败，**拒绝创建工单**，不建立 `ENTITLEMENT_REVIEW` 或空 SLA 实例。合同特批功能后置到 P1。初始化数据须包含所有演示场景的路由/SLA 映射；缺配置返回服务端配置错误而非默认为低等级策略。

`owner_team_id` 随工单创建固定。负责团队、工程师所属团队和主管管理范围相互独立：

- `service_ticket.owner_team_id`：工单的负责团队，由 `service_type_routing` 决定。
- `team_member`：工程师属于哪些团队；P0 派工只能选所属团队匹配工单负责团队的工程师。
- `team_manager_scope`：主管可以管理哪些团队；请求时实时检查。

AI 的故障 `category` 不重新路由工单，也不重算 SLA。

## 4.2 当前 SLA 版本唯一性

- `sla_policy_version`：保存不同版本的冻结规则；已发布/已引用版本不允许原地更新业务参数。
- `sla_policy_binding`：主键 `(service_tier,service_type,priority)`，映射到当前生效的**唯一版本 ID**。
- MySQL 组合外键保证映射中的三个匹配维度与目标策略版本一致。
- 创建工单时将策略版本 ID 写进 `ticket_sla_instance`；后续更换绑定仅影响**新工单**。
- P0 `calendar_mode = ALWAYS_ON`，不建立节假日配置界面；P1 引入版本化工作日历时需要重新评审时钟算法。

## 4.3 SLA 双时钟的 P0 统一口径

| 项目 | P0 定义 |
|---|---|
| 计时起点 | 合同与配置检查通过后、工单写入时的统一 `created_at`，UTC |
| 首次响应时钟 | 固定 7×24；**有权限的真人客服/工程师发给客户的第一条有效公开答复**才停止；AI 自动回复/内部备注不计入 |
| 解决时钟 | 固定 7×24，从起点累计；在 `PENDING_ACCEPTANCE` **暂停**，客户拒绝后按原累计时间恢复，不重置 |
| 客户补充信息 | 保留活动消息；**P0 不触发等待客户状态，也不暂停 SLA** |
| 等待验收 | 解决时钟暂停，首次响应时钟继续运行直到首次真人有效回复为止 |
| 验收通过 | 解决时钟结算，若始终未有真人有效首响则首响不达标 |
| 验收拒绝 | 保存当前验收轮次，创建整改任务，解决时钟从拒绝时刻继续累计 |
| 主动取消 | SLA 状态 `CANCELLED`，不计入正常完成的解决 SLA 达标分母；单独统计取消数量及原因 |
| 已超期再暂停 | 保留之前累计超时事实，不通过暂停洗掉违约历史 |

算例：解决目标 4 小时，运行 3 小时后提交解决方案，暂停 2 小时，客户拒绝，再处理 1.5 小时并验收，则有效解决时长为 4.5 小时，**不达标**；但实际自然历时为 6.5 小时，可单独统计。

对外消息必须由 `ticket_activity` 写入并在同一事务内更新 `ticket_sla_instance.first_responded_at/response_met` 和追加 `ticket_sla_event`。审批草稿、AI 自动作答均不自动生成首次人工响应事件。

`resolution_due_at` 在 `PAUSED_ACCEPTANCE` 状态可为空，恢复时按剩余有效秒数重新计算。使用注入的 `Clock` 获取一致时间；不要在一个业务命令中到处调用 `now()`。

---

# 5. AI 受理主任务、证据规则与数据契约

## 5.1 AI 在 P0 到底做什么？

客户说：“升级后页面经常卡住，下午访问多时更严重，提示数据库连接超时。”

AI 的职责是生成**可被检查的建议**：

1. 从原始输入识别已明确陈述的症状、触发时间、产品/版本、影响范围等字段；没有明确提供则标 `UNKNOWN`。
2. 生成少量最重要的补充提问，而不是每次把全部字段问一遍。
3. 使用已审核、适用的故障知识，提出初步诊断路径；明确哪些只是可能原因。
4. 提供知识版本和片段引用；没有足够证据时说明不确定，并提出需要补充的数据。
5. 客服修改/确认后写入工单受理记录，但**不会由 AI 改动合同、服务类型、优先级、SLA 或负责团队**。

P0 不启动 LLM 自主工具循环：固定管线是 `读取授权工单与客户显式申报的产品信息 → 确定性适用性过滤与关键词检索 → LLM 结构化生成 → Java 校验 → 人工确认`。检索前**不使用模型的产品/版本猜测作为过滤条件**。

## 5.2 结构化输出格式（示意，不是可直接运行的 JSON Schema）

```json
{
  "category": "DATABASE_CONNECTION",
  "observedFacts": [
    {"field": "symptom", "value": "数据库连接超时", "sourceQuote": "提示数据库连接超时"},
    {"field": "trigger", "value": "高访问量时更频繁", "sourceQuote": "下午访问多时更严重"}
  ],
  "unknownFields": ["productVersion", "errorLog"],
  "followupQuestions": [
    {"field": "productVersion", "question": "升级前后分别是什么版本？", "why": "排查升级后兼容性变化"}
  ],
  "diagnosticSuggestions": [
    {
      "claimType": "HYPOTHESIS",
      "content": "优先排查连接池等待和耗尽情况",
      "knowledgeRefs": [{"articleVersionId": 201, "quote": "连接池耗尽可表现为连接获取超时"}],
      "preconditions": ["确认该产品及版本适用"]
    }
  ]
}
```

**验证规则：**

- `sourceQuote` 必须能在保存的原文或指定客户补充消息中准确找到；不能把推测写成客户已说的事实。对于没有来源的客服补充，另存 `human_notes`，不冒充原文。
- `knowledgeRefs.articleVersionId` 必须来自本次检索返回的**已审核版本**；其产品及版本须匹配；适用前提未知时不能给出确定性诊断。
- “引用 ID 真实”只是语法校验，**并不证明引文支持结论**。需要可审核的引用片段，并在评测中由人工标注是否支持当前建议。
- 没有合理来源时，输出“证据不足/需要追问”，不能捏造解决记录、确定根因或生产配置。
- 所有字段经 Java 枚举/长度/类型/引用校验后才能形成可审阅草稿；失败则进入人工模式，不能部分执行未经验证的建议。

### 5.2.1 产品/版本来源与未知信息降级（P0 冻结）

产品身份与 `service_type`（合同服务类型）**不是一个概念**，也不能由故障 `category` 反向改写。第一版不建产品管理后台，只维护一个种子配置中的合法产品代码目录。工单新增两个**可空、客户显式申报**的字段：`reported_product_code`、`reported_product_version`。

1. 客户创建工单时可选择合法产品代码并填写版本；空值合法。值及来源标记为 `CUSTOMER_REPORTED`，不是已验证的系统事实。
2. 如果客服请客户补充产品/版本，可通过现有 `POST /tickets/{id}/messages` 的**客户补充消息**显式携带这两个可选结构化字段（仅允许 `SUBMITTED` 状态）；Java 在同一个工单锁事务中更新申报字段、保留消息与审计、递增工单版本。不能从模型输出偷偷更新这些字段；客服在 `confirmTriage` 中同样不得修改。仅提交普通文本时不自动将 AI 推测升级为结构化申报信息。
3. 构造 AI 请求快照时读取当前 `reported_product_code/version` 与客户原文、补充消息；保存本次实际使用的产品/版本和客户来源于 `ai_triage_run.retrieved_evidence_json`，并纳入 `context_sha256`。任何追加消息或显式更新均让旧建议版本失效。
4. 所有内容标注来源：`CUSTOMER_REPORTED`、`AI_EXTRACTED_CANDIDATE`、`SUPPORT_CONFIRMED`。P0 检索仅把第一种**明确提供**的产品代码/版本作为适用范围筛选输入；模型候选信息只能作为追问，不能当已确认事实或依据它作确定性诊断。客户申报也可能错误，所以诊断仍须展示适用前提并经客服核对。

| 检索输入条件 | P0 可用知识 | 禁止行为 | 追问策略 |
|---|---|---|---|
| 产品代码、版本均已由客户明确提供 | `GENERAL` 通用知识，以及对应产品代码且**精确覆盖该版本**的已审核条目；产品专属 `ALL` 版本也可用 | 将版本不兼容知识当成依据 | 必要时核对报障产品与版本 |
| 产品已知、版本未知 | `GENERAL` 通用知识与该产品 `applicable_versions=ALL` 的版本无关条目 | 用版本专属条目断言确定根因 | 询问具体版本 |
| 产品未知（即使客户仅报版本） | **仅** `product_code=GENERAL, applicable_versions=ALL` 的通用知识 | 根据相似语句猜测产品并选取专属知识 | 首先追问产品，再确认版本 |
| 无适用知识 | 不输出引用型的确定性诊断 | 编造文献或把无关引用伪装成证据 | 说明证据不足，建议补日志/环境信息 |

**小范围版本约定：** `knowledge_article_version.product_code='GENERAL'` 且 `applicable_versions='ALL'` 表示全产品通用；其他产品的 `applicable_versions` 在 P0 仅允许 `ALL` 或“逗号分隔的完整版本号白名单”，精确比较分隔后的版本值，**不用字符串子串匹配**（例如 `1.2` 不应匹配 `11.2`）。不在 P0 引入任意 SemVer 范围表达式。产品未知时不允许返回产品专属知识作为可执行建议。

**A0/A1/A2 对照公平性：** 三组使用相同的客户原文、显式申报的产品信息和固定版本知识库。A0 与 A2 使用同一过滤器/检索器；A1 与 A2 输出 Schema 和校验器一致，A2 仅多获得实际检索证据。检查集须包含“完整已知 / 仅产品已知 / 产品未知 / 错误申报”的案例，并分层报告结果。

## 5.3 客服受理允许写哪些字段？

| 字段 | 是否允许在 `confirmTriage` 中修改 | 原因 |
|---|---|---|
| `ticket.category` | 是，由客服确认 | 诊断分类，不承担合同或路由作用 |
| `ticket_triage_record.confirmed_facts_json` | 是 | 人工确认的结构化现象，不覆盖客户原文 |
| `confirmed_followups_json` | 是 | 客服审核后的追问草稿；需要单独点击发送才能成为客户消息 |
| `confirmed_diagnostics_json` | 是 | 保留审核过的建议和引用 |
| `service_type/owner_team_id/priority/contract_id` | **否** | 决定权限/合同/SLA 的确定性字段 |
| `original_description` | **否** | 审计用客户原始事实 |
| `sla_policy_version_id` | **否** | 工单已冻结 SLA 规则 |

人工直接受理可走相同的 `confirmTriage`，此时 `proposalId=null`；带 AI 草稿时 `proposalId` 必须指向当前工单且处于 `PENDING_REVIEW`。

## 5.4 AI 观察快照版本协议

```text
短事务/一致性读取时构建 observed_ticket_version = 12
       ↓ 记录 context_hash、原文摘要、知识版本 ID、模型与 Prompt
释放数据库连接/锁
       ↓ 云模型推理（限时、限并发）
生成候选输出 → 严格结构和证据校验
       ↓ 短事务：锁 service_ticket，再查当前版本和状态
    ├─ 仍是 12 且 SUBMITTED → 创建待审草稿，expected_ticket_version=12
    └─ 已变化/已受理 → run 标记 STALE，不创建可审批草稿
       ↓ 客服打开草稿、编辑确认
确认时锁工单后**再次检查**权限、版本、草稿状态和知识有效性
    ├─ 有效 → 受理记录 + ticket.status=TRIAGED + ticket.version++ + 审计，同事务提交
    └─ 过期/变更 → 草稿 STALE/EXPIRED + 审计，同事务提交，返回业务冲突
```

原则：`expected_ticket_version` 必须来自**模型实际读取的数据**，绝对不能用模型返回之后的最新版本替代。模型调用全程不能持有数据库锁。客户补充说明、客服人工受理、业务后台修改都会增加工单版本并使旧草稿失效。

只记录足以复核的数据版本、最小化上下文摘要、客户显式申报的产品/版本以及引用；**避免把合同秘密、完整客户日志或 API Key 直接写入模型提示及审计日志**。

## 5.5 鉴权拒绝与草稿业务失效分离

| 情况 | HTTP | 草稿/工单是否变化 |
|---|---:|---|
| 未登录 | 401 | 均不变 |
| 无客服角色、无工单访问权限 | 403（或不暴露资源的 404） | 均不变 |
| 客服有权，但工单版本与草稿不同 | 409 `AI_PROPOSAL_STALE` | 草稿改 `STALE` 并记审计；工单不修改 |
| 客服有权，但草稿超过有效期 | 409 `AI_PROPOSAL_EXPIRED` | 草稿改 `EXPIRED` 并记审计 |
| 客服修改非法字段 | 422 `TRIAGE_FIELD_NOT_EDITABLE` | 均不变 |
| 版本一致，客服审核确认 | 200 | `proposal=APPLIED`、`ticket=TRIAGED`、新增受理记录与审计 |
| 人工无 AI 直接确认 | 200 | 同样使用领域服务，`proposal_id` 为空 |

“业务过期”不能通过抛出默认回滚异常来丢掉已写入的 `STALE/EXPIRED` 与审计；服务层返回**业务拒绝结果对象**，事务提交后由 Controller 映射 409。真正 SQL/事务失败则正常回滚。

## 5.6 AI 运行限制（建议默认配置，需按模型实际测试调整）

| 约束 | 计划默认值 | 用途 |
|---|---|---|
| 单次模型总超时 | 20 秒 | 避免请求长期挂起；实际还须配置客户端连接/读取超时 |
| 自动重试 | 最多 1 次（总共 2 次尝试） | 仅暂时性网络/限流错误，限定退避；不重试格式错误或权限拒绝 |
| 同时推理请求 | 2 个 | 单机 16GB、本地轻量开发，超出返回 AI_BUSY/排队上限 |
| 客户描述输入 | 最多 4000 字符 | 限制提示注入面积与成本；字符不是 Token |
| 检索正文总量 | 最多 8000 字符 | 控制知识上下文大小 |
| 生成 Token 上限 | 建议 1024 | 依模型能力配置，不保证每家服务完全支持 |
| 自主 Tool 调用轮数 | **0** | P0 固定工作流，无模型自主写工具 |
| 去重机制 | 生成请求使用 `generation_key` | 相同请求键返回原运行记录，不生成第二份待审批草稿 |

`ai_triage_run` 保存规范化请求摘要、状态、耗时、模型与 Prompt 版本、知识集哈希、输入/输出 Token（供应商提供时）、失败类型；如果模型不返回用量则保留 `NULL`，不要编造估算值当实测。

进程崩溃后，未完成的运行记录经恢复检查可标 `FAILED`，由真人重新发起明确的新生成请求；云模型的调用/计费**不具备端到端 exactly-once 保证**。相同 `generation_key` 的重复请求必须比较**规范化请求摘要**：摘要相同返回原运行记录，摘要不同返回 409。

### 5.6.1 已存在待审草稿时的替换判定（不得误把旧版本当有效）

在发起模型请求**之前**，可先在工单锁下检查并处理已有 `PENDING_REVIEW` 草稿，避免明显重复调用。即使已做预检查，**模型返回后保存时仍必须在新的短事务内重新判断**，不能依赖预检查结果。处理顺序：

1. 先验证当前操作人的身份与资源权限；未经授权直接拒绝，不能更改任何草稿状态。
2. `SELECT service_ticket ... FOR UPDATE`，校验当前仍为 `SUBMITTED`，并核对**新运行**的 `observed_ticket_version` 与当前版本。若新运行已经过期，标记**新 run** 为 `STALE`，不保存建议；不允许用当前版本覆盖原始观察版本。
3. 查询该工单当前的 `PENDING_REVIEW` 旧草稿（存在则锁定）；**先检查版本**：若 `old.expected_ticket_version != ticket.version`，旧草稿改为 `STALE`，写审计并释放唯一待审名额。
4. 若旧草稿版本一致，再检查 `expires_at`：已超时则改为 `EXPIRED`，写审计并释放名额。
5. 若旧草稿版本和时间均有效，则**保持旧草稿待审**；新运行状态写 `SUPERSEDED`，**不保存第二份 proposal**，因为 `ai_triage_proposal` 的合法状态中并没有 `SUPERSEDED`。
6. 只有没有有效待审草稿、且新运行观察版本仍与工单一致时，才在同一事务保存一份新的 `PENDING_REVIEW` 建议，写审计并提交。相同 `generation_key` 的重复请求返回原运行记录，不执行第二次生成或创建。

约束依旧由 MySQL `uk_one_pending_triage` 兜底；但唯一约束**不能代替业务判断“旧版本是否已经失效”**。两次并发新生成必须经过同一工单锁：一份成为待审，另一份运行状态 `SUPERSEDED`，且不能覆盖前者。


---

# 6. P0 的 AI 效果实验契约（先做小规模验证）

## 6.1 测什么，不测什么？

**目的：** 证明在故障受理的指定任务上，模型能否以可接受的质量、成本和时间帮助客服；不要求它在所有任务上超越规则，也不宣称具有不可替代性。

| 方案 | 输入与能力 | 实验用途 |
|---|---|---|
| `A0` 规则基线 | 相同工单输入、固定字段表与关键词/元数据知识检索；模板式提问或固定诊断模板 | 判断“没有模型”能做到什么 |
| `A1` 直接模型 | 相同工单输入、相同模型/Prompt 版本/输出 Schema 与校验器；**不附检索证据** | 判断 LLM 的语义提取与提问质量 |
| `A2` 检索增强模型 | 与 A1 相同模型/提示策略/输出 Schema/校验器，**只增加固定版本的检索证据** | 隔离检索证据对诊断质量的额外影响 |

**公平性要求：** A1 与 A2 的主要区别限定为证据上下文；使用同一模型、采样参数、固定输出 Schema、异常处理和人工评分标准。检索使输入 Token 增多是方案成本的一部分，必须实际报告；不能假装两组 Token 成本相同。

A0 的知识检索应当有与 A2 **同一知识库和相同检索器**可用；但需要把“规则分类/提问”和“检索质量/诊断内容”拆开报告，避免拿弱规则基线对完整知识增强方案来夸大模型能力。对产品/版本未知的输入，三组必须看到完全相同的申报字段和未知状态；单独报告未知信息降级是否合规。

## 6.2 数据集、标注与避免泄漏

- 起始样例建议 **20 条开发集 + 40 条固定检查集**，均为合成且可公开的故障请求；数量可增加，但不能把这些数量称为统计显著性保证。
- 按故障家族、产品版本与话术模板分组后划分，尽量避免同一案例的近义改写同时进入开发集和检查集。
- 标准标签包括：故障类别、客户已提供事实、必问关键缺失字段、允许的诊断建议和相关知识条目/适用条件。
- **开发集**用于 Prompt、规则和检索策略修改；**固定检查集**在配置冻结后运行。若查看检查集结果又据此调参，需标记该集已用于开发，另留新检查集复验。
- 如果有条件，邀请另一位参与者盲评部分“证据是否支持结论”和“追问是否有用”；否则清楚披露自建样例/自评偏差。
- 输出每条的失败案例、原始正确条数和错误类别；40 条检查集中单条误差就占 2.5 个百分点，不应只报好看的百分比。

## 6.3 指标拆开报

| 子任务 | 需要看哪些指标 |
|---|---|
| 故障类别 | 正确条数、准确率、Macro-F1、混淆矩阵 |
| 事实提取 | 字段级 Precision/Recall/F1；引用原文是否准确 |
| 缺失信息 | 必要问题 Recall + 额外/重复问题比例 + 不相关问题比例 |
| 知识检索 | 相关知识的 Recall@K、版本匹配、适用前提 |
| 排查建议 | 证据支持率、适用条件检查、误把猜测说成事实的条数 |
| 工程开销 | 模型请求耗时分布、超时率、失败类型、Token、可核实的费用 |
| 客服实际收益（可选） | 人工修改次数、审核时长、采纳率；受样本与标注人限制 |

**引文质量分两步：**（1）引文确实来自检索结果并匹配产品版本；（2）引文的内容**实质支持**对应建议。第（2）步不能只通过“ID 存在”证明，P0 采用规则校验 + 人工评分，必要时单独研究自动判定。

## 6.4 安全与效果是两套验收

- **确定性安全门槛：** 客服越权或客户越权必须拒绝；模型文本不得改写合同/团队/SLA；旧建议不得套上新版本；重复确认不得重复受理；不允许 AI 未经批准写入业务。
- **模型质量评估：** A0/A1/A2 对比能否改善信息提取、追问或建议，并记录成本。允许“分类保留规则，提取交给模型，诊断采用检索增强”这样的混合结论。
- 不设未经基线测量就凭空拍脑袋的“全维度必须提高 20%”目标。先预注册比较方法与安全门槛，然后诚实报告实测。

---

# 7. P0 Java 模块、接口与统一错误码

## 7.1 最小模块化单体

```text
serviceflow-backend/src/main/java/.../
  identity/        登录身份、客户与团队资源范围
  contract/        合同与权益检查
  ticket/          聚合根、生命周期、受理记录、活动消息
  task/            活动任务、派工历史、任务状态
  sla/             7×24 双时钟、事件与策略绑定
  knowledge/       审核知识的元数据/关键词检索
  ai/              快照、结构化建议、运行限制、受理草稿
  audit/           操作审计与幂等记录
  shared/          错误码、时间、数据库和 API 公共设施

serviceflow-frontend/  Vue3（登录、客户提交/详情、客服受理、主管派工、工程师工作台）
```

P0 身份、合同、团队、SLA 策略、知识文章可由版本化 SQL + 演示种子数据配置，不要求所有功能都有后台 CRUD 页面。

## 7.2 关键 API（建议的 P0 路径）

| Method + Path | 谁调用 | 核心行为 |
|---|---|---|
| `POST /api/v1/tickets` | 客户 | 根据合同/团队/SLA 确定性创建工单；可选客户显式申报的 `reportedProductCode/Version` |
| `GET /api/v1/tickets/{id}` | 有权用户 | 工单状态、可见活动、SLA 信息 |
| `POST /api/v1/tickets/{id}/messages` | 客户/客服/工程师 | 追加信息或真人公开答复；仅客户在 `SUBMITTED` 的补充消息可选更新显式产品/版本申报，工单版本递增；真人有效答复才停止首响时钟 |
| `POST /api/v1/tickets/{id}/ai-triage-runs` | 客服 | 创建异步推理运行，返回 `202 + runId`；重复键返回原 run |
| `GET /api/v1/ai-triage-runs/{runId}` | 授权客服 | 查看进度、校验通过的草稿/错误类型 |
| `POST /api/v1/tickets/{id}/triage` | 客服 | 人工直接受理，或确认/编辑 AI 草稿，只写允许字段 |
| `POST /api/v1/tickets/{id}/tasks` | 主管 | 初次创建一项活动阻塞任务；非任意整改写入 |
| `POST /api/v1/tasks/{id}/assignments` | 主管 | **首次派工**：仅任务 `CREATED`，首轮工单进入 `ASSIGNED` |
| `POST /api/v1/tasks/{id}/reassignments` | 主管 | **转派**：任务 `ASSIGNED/ACCEPTED/PROCESSING → ASSIGNED`，工单状态不变，历史保留 |
| `POST /api/v1/tasks/{id}/accept` | 工程师 | 接单 |
| `POST /api/v1/tasks/{id}/start` | 工程师 | 开始处理 |
| `POST /api/v1/tasks/{id}/complete` | 工程师 | 完成任务 |
| `POST /api/v1/tickets/{id}/resolutions` | 工程师 | 提交解决方案，暂停解决 SLA |
| `POST /api/v1/resolutions/{id}/acceptance` | 工单创建者 | 同意关闭或拒绝并创建整改任务 |
| `GET /api/v1/tickets/{id}/sla` | 有权用户 | 查看已冻结策略和当前 SLA 计时信息 |

所有写入请求对非幂等命令携带 `Idempotency-Key`；基于旧页面状态修改的命令携带 `expectedTicketVersion`，任务命令另带 `expectedTaskVersion`。AI 生成请求使用 `generation_key`，由客户端在重试时保持不变。

### 受理确认请求示例

```json
{
  "expectedTicketVersion": 12,
  "proposalId": 90001,
  "confirmedCategory": "DATABASE_CONNECTION",
  "confirmedFacts": [{"field": "symptom", "value": "连接超时", "sourceQuote": "提示数据库连接超时"}],
  "confirmedFollowups": [{"field": "productVersion", "question": "升级前后版本分别是什么？"}],
  "confirmedDiagnostics": [{"content": "检查连接池", "claimType": "HYPOTHESIS", "knowledgeRefs": [201]}]
}
```

*注意：* 该接口**没有** `serviceType`、`ownerTeamId`、`priority`、`slaPolicyVersionId` 等字段；服务端还需拒绝未知/非法字段，不能因为 DTO 没声明就悄悄接受修改行为。

### 返回实例

```json
{
  "ticketId": 1001,
  "status": "TRIAGED",
  "ticketVersion": 13,
  "triageRecordId": 5001,
  "source": "AI_REVIEWED"
}
```

人工确认可用 `proposalId: null`，其他校验不变。确认受理**不自动完成首次响应 SLA**，客服还需要明确向客户发送真人公开消息。

### 错误码

| HTTP | 业务码 | 场景 |
|---:|---|---|
| 401 | `UNAUTHENTICATED` | 未登录 |
| 403/404 | `RESOURCE_FORBIDDEN` | 无权访问客户或负责团队 |
| 409 | `VERSION_CONFLICT` | 工单/任务版本不符 |
| 409 | `ACTIVE_TASK_EXISTS` | 已经有活动阻塞任务 |
| 409 | `AI_PROPOSAL_STALE` | AI 建议根据旧版本生成 |
| 409 | `AI_PROPOSAL_EXPIRED` | 草稿有效期已过 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 相同幂等键请求内容不同 |
| 422 | `TRIAGE_FIELD_NOT_EDITABLE` | 尝试修改冻结业务字段 |
| 422 | `PRODUCT_IDENTIFICATION_INVALID` | 申报的产品代码不在种子目录、格式不符合约束 |
| 422 | `INVALID_STATE_TRANSITION` | 当前状态不允许操作 |
| 422 | `CONTRACT_NOT_ELIGIBLE` | 合同已过期或服务不覆盖 |
| 429 | `AI_BUSY` | 推理并发达到上限 |
| 503 | `AI_TEMPORARILY_UNAVAILABLE` | 模型不可用，业务仍可人工受理 |
| 500 | `SLA_POLICY_CONFIGURATION_ERROR` | 策略映射缺失或配置冲突 |

---

# 8. SQL 与状态契约的映射

完整初始建表见 [ServiceFlow_AI_P0_schema_V0.3.1.sql](./ServiceFlow_AI_P0_schema_V0.3.1.sql)。它是**新库的统一 P0 建表草案**。如已在测试环境建立 V0.3 新库，可参考 [V0.3→V0.3.1 增量](./ServiceFlow_AI_P0_patch_V0.3_to_V0.3.1.sql)；后者不是生产环境数据迁移方案。

| 新/调整对象 | 决定了什么 |
|---|---|
| `service_ticket.owner_team_id, routing_version` | 工单归属有确定事实，后续团队路由改变不悄悄改变旧工单 |
| `service_ticket.reported_product_code/version` | 客户显式申报的可空产品/版本来源；客户消息更新需要工单锁、版本递增和审计，模型不能填写 |
| `team_manager_scope` | 主管管理哪些团队；不直接由角色推断全部权限 |
| `service_type_routing` | 服务类型到负责团队的唯一当前映射 |
| `sla_policy_binding` | 当前生效的 SLA 版本唯一选择 |
| `service_task.active_blocking_ticket_id` | 生成列 + UNIQUE 防止两个活动阻塞任务并存 |
| `ticket_sla_instance` | 7×24 双时钟投影；仅验收等待暂停解决时钟 |
| `ticket_resolution/ticket_acceptance` | 唯一解决轮次与验收决策，拒绝创建整改任务 |
| `knowledge_article_version` | 已审核知识的不可变正文版本与适用条件 |
| `ai_triage_run` | 模型实际观察版本、知识版本、模型配置与调用结果 |
| `ai_triage_proposal` | 可审核的结构化草稿，至多一个 `PENDING_REVIEW` |
| `ticket_triage_record` | 原文之外的人类确认结果；每张工单 P0 一次受理记录 |
| `business_audit_log/idempotency_request` | 操作证据与重复提交保护 |

**DDL 不能独立保证所有跨表不变量。** 例如 `proposal.ticket_id` 必须与 `run.ticket_id` 相同、客服团队可访问范围、知识引文实质支持结论，仍需 Java 应用服务与集成测试验证。

当前 SQL 草案约 25 张逻辑表，但这是把用户、权限、版本、运行证据、审计拆开保存的结果，**不代表 P0 要开发 25 个管理页面**。多数基础数据只通过初始化脚本创建。若实际开发发现非必要表阻碍最小闭环，可在不破坏上述契约前提下进一步合并。

---

# 9. 实现顺序与两项先行验证

## 9.1 不急着开发所有页面

| 阶段 | 交付内容 | 通过条件 |
|---|---|---|
| `S0` 设计检查 | 本文、Schema 静态审查、数据种子设计 | 状态、权限、AI 写字段、SLA 和并发规则没有矛盾 |
| `S1` Java 最小纵切 | 登陆、客户合同校验、创建/受理工单 | 通过正常提交、非法合同、跨客户访问测试 |
| `S1-AI` AI 独立实验 | 20 开发 + 40 固定检查样例，A0/A1/A2（含产品/版本未知分层） | 能报告条数、失败、证据与成本，不能只演示几条正确回答 |
| `S2` 工单交付闭环 | 创建任务、首次派工、转派、处理、提交解决方案、验收整改 | 真实 MySQL 并发场景、旧负责人失权、唯一任务和轮次验证通过 |
| `S3` SLA + AI 业务接入 | 双时钟、AI 受理草稿人工确认、历史与审计 | 模型故障降级、旧草稿拒绝、SLA 暂停与恢复通过 |
| `S4` 全栈展示 | Vue3 客户/客服/主管/工程师页面、Docker | 16GB 本地能运行，端到端业务可复现 |

AI 小实验并非等 S2/S3 全部完成才开始；只需 S1 的工单原文和基础权限即可，并可与其余业务开发并行。

## 9.2 两个最快能发现方向问题的技术验证

**验证 A：真实 MySQL 工单并发**

- 至少两线程、两数据库连接，控制提交解决方案与另一业务变更的竞争时序。
- 断言：不出现 `PENDING_ACCEPTANCE` 且带未完成活动阻塞任务；不出现两个活动任务；版本和审计一致。
- 再验证验收同意与拒绝并发、重复 `Idempotency-Key` 行为。
- 结果保存可重复的 JUnit + Testcontainers 测试及 SQL 数据检查结果。

**验证 B：真实模型故障受理对照**

- 固定同一个云模型服务、Prompt 版本、知识集、Schema 与调用上限。
- 运行 A0/A1/A2，生成结果逐条比对人工标注。
- 按症状提取、缺失问题、知识支持、耗时和费用分别报告；先开发集调试，再运行未参与调参的检查集。
- 若整体质量不佳，分析到底是结构提取、知识检索还是生成阶段失败；允许采用“规则分类 + 模型提取 + 检索增强建议”的混合架构。

> **不要先编造准确率、并发性能或模型效果。** 本版只冻结可验证的方法，实测数据将在代码和实验出现之后填写。

---

# 10. 编码前待确认项（尽量少）

| 项目 | 本版建议值 | 是否应阻塞对应模块编码？ |
|---|---|---|
| 单工单活动任务上限 | 1；`CREATED` 也算活动 | 是，已明确 |
| P0 SLA 日历 | 7×24，仅验收等待暂停解决时钟 | 是，已明确 |
| AI 更新边界 | 仅分类与结构化受理结果；业务冻结字段不变 | 是，已明确 |
| SLA 当前策略映射 | `sla_policy_binding` 唯一键 + 策略版本快照 | 是，已明确 |
| 工单团队来源 | `service_type_routing`，创建时固定 | 是，已明确 |
| AI 生成运行参数 | 20秒/2并发/1次重试/0自主工具轮次为起始值 | 需模型接入实测调整 |
| 故障分类枚举 | `DATABASE_CONNECTION` 等类别由种子配置定义 | 开始模型评测前确定 |
| 开发集/检查集 | 20/40，避免近重复模板泄漏 | 开始效果评测前确定 |
| 依赖版本 | Java 21 + Spring Boot 3.x + MySQL 8 + LangChain4j | 建工程时锁定具体兼容版本 |
| 代码与 SQL 验收 | 用户报告 V0.3 原始 SQL 已在 MySQL 8.0.46 检查通过；V0.3.1 新增字段待复验；JUnit 和模型实验未执行 | 分清不同版本与证据来源 |

## 结论

V0.3.1 不再追求首版功能数量，而是把个人项目真正有价值的两件事做成可验证的工程成果：

1. **Java 正确的复杂业务执行：** 合同、权限、工单/任务状态、7×24 双 SLA、并发和客户验收能够形成完整闭环。
2. **AI 可测量、可审计地帮助真实业务：** 从非结构化故障描述提取信息、检索有来源的知识、提出追问和排查建议，由客服审阅后有限写入工单受理记录。

这既保留企业业务深度，也避免 P0 退化成普通 CRUD + RAG 聊天窗口。V0.3 原始 SQL 的部分约束已有用户报告的数据库证据，但 V0.3.1 新增字段、Java 工单锁/权限/事务以及模型质量仍需分别运行验证；不能把设计文档当成已完成的工程证据。
