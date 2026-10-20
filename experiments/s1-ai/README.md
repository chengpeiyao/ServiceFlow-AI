# S1-AI 独立故障受理对照实验

本目录用于验证受理辅助价值，不连接业务数据库，不修改原始工单，不包含 AI 草稿审批或派工流程。S1 基线已固定为本地 Git 标签 `s1-java-verified`，对应提交 `840229c`。

## 数据与知识的可信边界

- `datasets/dev.jsonl`：20 条开发案例；`datasets/fixed.jsonl`：40 条固定检查案例，按故障家族分开。输入和标注分别保存在 `input`、`gold` 中，模型仅接收原文和客户显式申报的产品、版本；不接收故障家族、分类标注或标准答案。
- 这 60 条均为项目合成案例，标注是待领域人员复核的初稿，不能当作真实客户、独立专家标注或生产效果证据。故障家族名称不重叠不等于不存在语义相似，正式实验前仍需人工复核近似案例与标注。
- `knowledge/knowledge.jsonl`：24 条知识，其中 22 条 `APPROVED_DEMO`，1 条草稿和 1 条通用产品错配版本用于排除验证。`APPROVED_DEMO` 只代表本项目演示资料经作者做过只读操作与适用范围检查，不代表企业专家审批。所有 COMS/IOT 版本行为均为模拟设定，不可作为真实产品操作手册。
- 知识的前置条件、版本白名单和禁止直接改数据的边界必须保留。静态引用校验通过不代表建议得到语义支持。
- 标签和知识经人工复核有改动时，数据哈希会变化。不得把不同数据版本的结果混在同一对照中。

A3 当前候选已[冻结源码、数据、证据与原运行 JAR](./freezes/a3-v01-2026-10-20/README.md)，不接入 P0 业务。S2 Java 任务执行独立推进；本轮没有继续调 Prompt 或运行固定集。后续 AI 价值判断重点使用[逐条诊断证据评分](./review/Evidence_Review_Guide.md)，全部人工评分仍待填写。

## 四个实验组

| 组 | 实现 | 模型可见证据 |
|---|---|---|
| A0 | 固定关键词分类、正则提取、字段缺失检查、关键词检索，直接展示知识原文 | 无模型 |
| A1 | 同一模型结构化提取、追问、待核实建议 | 空证据数组 |
| A2 | 同一模型结构化提取、追问、带来源建议 | 检索到且适用的知识，最多 3 条 |
| A3 | 保守混合：A0 分类与追问字段，模型事实/措辞/证据建议，失败显式降级 A0 | 与 A2 同一检索器，另含无标签的规则计划 |

A1/A2 使用同一个客户端配置、系统提示词、输出 Schema、校验器和评分配置；只有 `retrievedEvidence` 不同。无证据时必须声明证据不足，引用字段用空字符串。分类采用 S1 的六类固定编码，设备原因未知用 `OTHER`，性能/数据一致性用 `APPLICATION`，时钟/单位/告警配置用 `CONFIGURATION`；信息不足另设 `categoryInsufficient` 标记。检索关键词仅来自客户原文，产品过滤仅来自显式申报，不使用模型猜测。产品未知只取 `GENERAL/ALL`；版本未知排除版本专用知识；`1.2` 不匹配 `11.2`。A0/A2 共享检索器。

先对显式申报和原文做确定性检索，再由一次模型调用完成提取与建议，避免额外调用使 A1/A2 产生不公平的成本差异。该阶段不引入向量库或多轮工具调用。

当前提示词版本 `s1-ai-intake-v0.3`：模型输入的 `customerInput` 仅包含 `description`，显式产品和版本放在独立的 `readOnlyReportedContext`，不作为原文引文来源。仅当产品或版本也确实出现于 description 中，才允许对应事实引用；原文与显式申报冲突时仍可追问澄清。`change` 只表示明确的前置变更，不把症状或推测原因当作变更。

事实字段和追问字段各自不能重复；校验器保留原有引文拒绝，并增加 `METADATA_QUOTE_NOT_IN_DESCRIPTION` 来源诊断和 `DUPLICATE_QUESTION_FIELD` 拒绝。不自动删改、合并或补写模型事实。提示词要求简短建议与必要追问，输出 Schema 和 1024 Token 限制保持不变。实际是否遵守仍需开发集和人工评审验证，不能仅凭提示词内容宣称问题已解决。

A3 单独使用 s1-ai-hybrid-v0.1，不属于 A1/A2 的“仅证据不同”实验。其分类和追问指标包含确定性规则，必须结合 modelCandidateAcceptedCases、ruleFallbackCases、templateQuestionCount 与原始模型输出判断增益。A3 保守版继承 A0 问题集合，不声称解决过度追问。人审表中 A3 output 为最终输出，原始模型候选另存。具体结果见 [混合实验记录](../../docs/verification/ServiceFlow_AI_S1_AI_Hybrid_2026-10-20.md)，标签定义及逐事实语义评分见 [复核说明](./review/README.md)。

## 运行

先在 `backend` 使用 JDK 21 执行 `mvn verify`。无 Docker 时可沿用 `backend/scripts/Test-LocalMySql.ps1` 启动独立临时 MySQL 并运行全套验证。

在项目根目录运行：

```powershell
.\backend\scripts\Run-S1Ai.ps1 -JavaHome '你的JDK21目录' -Split dev -Arms A0
```

运行器新增 `-Concurrency 1` 或 `-Concurrency 2`（默认 2，上限仍为 2）。本机 Ollama 内容调试可选 1，以减少本批请求之间的队列等待干扰；两模型组使用同一值，实际值与上限分别记录在 manifest 中。20 秒请求超时不变。

```powershell
.\backend\scripts\Run-S1Ai.ps1 -JavaHome '你的JDK21目录' -Split dev -Arms 'A0,A1,A2' -Concurrency 1
```

运行四组开发集对照：

```powershell
.\backend\scripts\Run-S1Ai.ps1 -JavaHome '.\tools\java\jdk-21.0.12.1+1' -Split dev -Arms 'A0,A1,A2,A3' -Concurrency 1
```

模型配置放在用户指定的 `experiments/s1-ai/config/model.local.properties`，该目录已加入 `.gitignore`。模板是`config/model.example.properties`，需填写完整接口地址、真实模型名及兼容参数。运行器只读 `keyEnvironment` 指定的环境变量，不读取 Codex 登录凭据，也不会自动从系统其他配置复制密钥。

```powershell
# 在同一 PowerShell 进程中安全输入，避免把密钥写进历史、配置或聊天。
$secret = Read-Host '输入模型 API 密钥' -AsSecureString
$env:SERVICEFLOW_AI_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
.\backend\scripts\Run-S1Ai.ps1 -JavaHome '你的JDK21目录' -Split dev -Arms 'A0,A1,A2'
# 正常退出后清除当前进程中的临时变量。
Remove-Item Env:SERVICEFLOW_AI_API_KEY
```

也可通过 `SERVICEFLOW_AI_ENDPOINT`、`SERVICEFLOW_AI_MODEL`、`SERVICEFLOW_AI_KEY_ENV`、`SERVICEFLOW_AI_TOKEN_PARAMETER`、`SERVICEFLOW_AI_RESPONSE_FORMAT`、`SERVICEFLOW_AI_TEMPERATURE` 配置；显式配置文件优先。外部服务要求 HTTPS，本机 `localhost`、`127.0.0.1`、`[::1]` 允许 HTTP；禁止 URL 内的账号、密钥、查询参数或片段。外部服务缺失密钥时拒绝启动；本机服务可不设置密钥，未设置时不发送认证头。

本机 Ollama 可以直接填写以下配置，其中 `model` 必须替换为本机已安装的模型名：

```properties
endpoint=http://127.0.0.1:11434
model=填写本机已安装的模型名
keyEnvironment=OLLAMA_API_KEY
tokenParameter=max_tokens
responseFormat=json_object
temperature=
```

服务根地址会自动补齐 `/v1/chat/completions`，也接受 `/v1` 或完整接口路径。`OLLAMA_API_KEY` 可不设置；为避免沿用云服务密钥变量，建议保留示例中的独立变量名。地址格式依据 [Ollama 官方兼容接口说明](https://docs.ollama.com/api/openai-compatibility)，采用兼容的 Chat Completions 接口，不使用原生 `/api/chat`。本次地址适配不代表已经验证具体本地模型的结构化输出质量；原有 20 秒请求超时与 1024 Token 限制仍适用。

如果服务商只支持 JSON mode，将 `responseFormat=json_object`；两组均使用同一模式并执行同一严格本地校验。结构化输出也可能遇到拒答或输出截断，必须保留失败状态，不能当成有效建议，参见 [OpenAI 官方结构化输出说明](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=chat)。本项目提供兼容 Chat Completions 的原生 Java HTTP 适配器，未添加模型 SDK 依赖；尚未验证各服务商兼容性。

提示词、Schema、规则、知识、模型参数和标签冻结后，再运行固定检查：

```powershell
.\backend\scripts\Run-S1Ai.ps1 -JavaHome '你的JDK21目录' -Split fixed -Arms 'A0,A1,A2,A3' -ConfirmFixed
```

固定集结果不得用于继续调参。如果根据结果调整规则或提示词，当前固定集即视为开发资料，需要另建检查集；程序中的确认开关只能防误操作，无法替代流程约束。不要自动重跑整批付费实验；中断后先检查已经刷盘的逐条结果。

## 约束和记录

描述最多 4000 字符、检索序列化证据最多 8000 字符、最多 3 条知识、输出最多 1024 Token、并发最多 2、每次请求超时 20 秒。仅对 HTTP 429 或 5xx 最多重试 1 次，等待 300 毫秒；其他错误、超时、无效 JSON、截断和拒答不重试。超时可能已计费，因此不自动重发。单次案例含重试的总时长可能超过 20 秒。

每次运行新建 `runs/<时间-随机标识>/`：

| 文件 | 内容 |
|---|---|
| `manifest.json` | 数据、Schema、实际加载的实验类字节码 SHA-256；模型与运行参数；价格来源 |
| `results.jsonl` | 逐案例原文、原始输出、通过校验后的输出、失败、逐次请求状态、延迟、实际返回模型名、Token、费用估算 |
| `summary.json` | 分组正确/错误条数、混淆矩阵、6 类 Macro-F1、事实精确匹配计数、追问计数、Recall@3、P50/P95、失败与已知用量 |
| `human-review.jsonl` | 隐去组名的人工判定表，默认全部未评分；证据内容仍可能使组别被推断，不宣称完全盲评 |
| `review-index.json` | 判定标识与案例/组的映射，供评分后汇总 |
| `completed.json` | 仅完整执行后生成；中断运行不得与完整实验混淆 |

模型拒绝、不合法输出或引用错误时，原始输出仍保存，但 `acceptedOutput=null`；自动指标按未正确完成计入，错误类型单独统计，不丢弃失败样本。`SCHEMA_EXTRA` 会拒绝 SLA、合同等任何未定义字段。这只保护独立实验输出，不替代未来业务接口的授权、幂等与事务控制。

`rawCategoryCorrect` 单独统计原始输出分类正确数；`categoryCorrect` 仍要求输出先通过校验。`acceptedCases`、`rejectedCases`、`acceptedCategoryWrong` 区分被拒绝与真正分类错误，不能把“引用失败但分类正确”说成模型分类错误，也不能只展示原始分类而隐去被拒绝的输出。其他原有评分口径不变，语义证据支持仍留给人工。

Token 缺失保存 `NULL`；记录所有尝试，不能只统计最后一次。A0 不调用模型，用量为不适用，不伪装成模型返回的零用量。费用默认 `NULL`。按模板填写用户核实的单价、币种、官方来源和核价日期后，可计算不扣缓存优惠的标价估算；任一尝试缺少用量，该案例估算仍为 `NULL`。真实账单字段 `cost/totalCost` 仍为 `NULL`，不把估算当作账单。已知用量合计或已知估算小计都不是完整费用。

## 人工评审与指标含义

自动事实评分仅比较字段和值的大小写/空白归一化后精确匹配，不是语义评分。A0 把整段描述当作粗粒度 `symptom`，因此会被精细短语标注判错；不能据此直接宣称模型更懂业务。应同时查看逐条输出并人工判断。

`extraQuestionCandidates` 表示不在必问标注中的问题，未必无关；`knownFieldQuestions` 表示向已知字段追问的候选错误，但标注明确要求澄清的产品/版本冲突不扣此项。`duplicateQuestions` 按相同字段重复统计，语义重复仍需人工判断。

人工逐条核查：事实是否遗漏或添油加醋；追问是否必要、有无重复；知识引用是否支持建议；版本和前置条件是否正确；是否把推测当成已确认事实；有无不应执行的操作。将 `human-review.jsonl` 复制为 `reviewed.jsonl`，不改输入、输出和证据，只填写以下 `true/false/null` 判定及 `reviewer/notes`：

| 字段 | true 的含义 |
|---|---|
| `factsSemanticCorrect` | 事实表达正确，重要事实无明显遗漏 |
| `questionRelevance` | 追问必要且无明显重复或已知字段追问 |
| `evidenceSupportsSuggestion` | 每条有依据的建议都得到给定证据支持；无引用或无建议可保持 NULL |
| `preconditionsSatisfied` | 建议正确保留适用范围，并明确提示尚待核实的前提；不代表真实现场前提已成立 |
| `factHypothesisConfusion` | 存在把推测当已确认事实的错误，true 表示负面结果 |

汇总人工判定（输出单位为案例，不是建议条数）：

```powershell
$jar = (Resolve-Path '.\backend\target\serviceflow-backend-0.1.0-SNAPSHOT.jar').Path
& '你的JDK21目录\bin\java.exe' '-Dfile.encoding=UTF-8' '-Dloader.main=com.serviceflow.experiment.ReviewSummaryCli' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher '运行目录' 'reviewed.jsonl的路径'
```

汇总工具检查未知/重复标识、资料篡改、评分类型和审核人；缺失或未评分项保留为 `unscoredCases`，不能当作正确。建议由第二人复核争议项，未完成评审前不得报告证据支持率提升。

## 本阶段结束标准

需要冻结后的 A0/A1/A2/A3 固定集记录、经复核的标签与知识、人工评审和可核查成本，才能比较故障类型收益并决定哪些能力保留 AI。当前已完成实验工具、A0 基线及本机模型 20 条开发集对照；模型分类与必问覆盖未优于规则，不代表 S1-AI 效果已验收，详见 [完善与结果记录](../../docs/verification/ServiceFlow_AI_S1_AI_Refinement_2026-10-20.md)。P1 的人工确认草稿写入、S2 派工均未实施，现有 S1 对 `proposalId` 的拒绝行为继续保留。

## 当前目录归属

配置见 [config](./config/README.md)，案例见 [datasets](./datasets/README.md)，语料见 [knowledge](./knowledge/README.md)，内嵌 Prompt 版本见 [prompts](./prompts/README.md)。仅调整文件位置与读取路径；A0～A3 的模型、规则、标签和评测口径保持不变。旧原始记录不改写路径，使用 [迁移映射](../../docs/verification/workspace-layout-2026-10-20/relocations.json) 定位。
