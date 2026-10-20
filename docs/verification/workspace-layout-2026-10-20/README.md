# 工作区目录规范化交付

本轮完成 49 个文件归属调整，其中 15 个已跟踪文件使用 git mv。未提交、重置或清空原有修改；原索引的文件内容摘要保持不变，仅重定位路径。

## 当前入口

- [文档导航](../../README.md)
- [项目目录归属](../../Project_Directory_Guide.md)
- [完整目录树，包含本机工具、缓存与 Git 内部文件名](./directory-tree.txt)
- [每个移动文件的旧路径、新路径和摘要 CSV](./moves.csv)
- [历史记录旧路径定位 JSON](./relocations.json)
- [内容摘要和引用验证](./hash-verification.json)
- [自动化测试汇总](./summary.json)
- [离线测试与构建完整日志](./offline-test.log)

## 归属与保留决策

docs 按 design、implementation、verification 分类，并提供每类索引。实际根配置目录原名为 config_al，只有独立实验模型配置，现归入 experiments/s1-ai/config；未创建不存在的 config_ai。后端 application*.yml 不动。

案例与知识分别放 datasets、knowledge。Prompt 仍在原 Java 代码中，prompts 目录只提供版本/源码导航。ModelClient、HybridTriage、标签、语料、业务 Java、SQL 和评测算法均不变，ExperimentData/ExperimentCli 仅替换文件读取与摘要键中的路径，测试仅同步目录准备路径。

artifacts/development 的所有日志、检查材料和变更前快照原样移入 docs/verification/local-history/development；该目录仍被 Git 忽略。冻结 JAR 移入原 A3 freeze 目录作为 runtime.jar，原 manifest 和 snapshot.zip 不改写。当前运行中的 Ollama 日志仍在 artifacts/runtime/ollama，避免中断已运行服务；后续 Java 临时文件改为 backend/.local/tmp。

历史 JSON、JSONL、XML、manifest、日志和压缩快照保留原字节及原哈希，旧路径由新增映射定位，不修改原始记录。移动文档中的当前导航链接已更新；所有受影响文档、脚本和源码的整理前原文以及 Git 差异保存在 .local/before-edits.zip 和对应补丁中，不纳入版本库。

## 实际检查

执行 .\scripts\Test-Project.ps1 -Offline，在本次进程 MAVEN_ARGS 中仅排除 ExperimentTest#allBaselineOutputsPassSharedValidation。现有 89 项通过：原实验 31、A3 工程 6、S1 27、S2 25，失败/错误/跳过均为 0；JAR 构建成功。

原测试中被排除的 1 项会对固定检查集执行规则基线，因此按本轮约束未运行，不计作通过。原加载器和数据完整性测试仍读取固定集以验证数量、家族隔离和标注格式，但未在固定集上执行新一轮规则或模型评测；真实模型实验调用为 0。AI 客户端工程测试只使用本地假 HTTP 响应。

Git diff --check 通过，3 个 PowerShell 脚本语法检查通过；当前 Markdown 本地链接有效，未发现 Obsidian 双链。140 个未改写文件摘要一致；A3 原始压缩快照的 27 个条目及原 JAR 摘要一致。两份 Java 实验源码与测试的差异按整理前快照逐字断言仅为列明的路径替换/目录准备。

## 修改的现有执行文件

| 文件 | 变更 |
|---|---|
| backend/scripts/Run-S1Ai.ps1 | 默认模型配置定位到实验 config |
| scripts/Test-Project.ps1 | Java/MySQL 临时目录改为 backend/.local/tmp |
| backend/src/main/java/com/serviceflow/experiment/ExperimentData.java | 读取 datasets、knowledge |
| backend/src/main/java/com/serviceflow/experiment/ExperimentCli.java | 新运行 manifest 的数据路径键匹配现目录，不改历史 manifest |
| backend/src/test/java/com/serviceflow/ExperimentTest.java | 临时测试资料目录同步路径；断言与评测逻辑不改 |
| experiments/s1-ai/config/model.example.properties | 模板注释中的放置位置更新 |
| .gitignore | 忽略本机临时目录、配置、冻结 JAR、历史本机材料和本轮私有原文快照 |

.vscode、pom.xml、tools、backend 应用配置及 SQL 均保持原位和原内容。Markdown 变更仅用于当前路径、导航、整理说明和原有缺失链接的明确标记；分类索引、目录指南以及实验 config/datasets/knowledge/prompts 的导航已提供。

## 尚未解决的原有问题

- V0.1 设计原文件在整理前缺失，仅保留缺失说明，不创建替代设计。
- 更早的 artifacts/migration-2026-10-20 和 artifacts/workspaces 已缺失，不宣称本轮恢复。
- 当前 Ollama 使用旧日志位置，本轮保留；后续如迁移须单独协调服务重启。
- IDE、Maven、本机模型配置及部分旧记录包含本机绝对路径；更换机器时应重新配置或使用映射，不能重写历史证据。
- 人工标签、语义和证据评分仍未完成；目录整理和构建通过不代表 AI 效果验收。

## 逐文件移动清单

| 旧路径 | 新路径 | 内容摘要 |
|---|---|---|
| `docs/ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_Design_V0.2.md` | `docs/design/ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_Design_V0.2.md` | 仅当前引用/注释更新；原文已留档 |
| `docs/ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_Design_V0.3.md` | `docs/design/ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_Design_V0.3.md` | 不变 |
| `docs/ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_P0_schema_V0.2.sql` | `docs/design/ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_P0_schema_V0.2.sql` | 不变 |
| `docs/ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_P0_schema_V0.3.sql` | `docs/design/ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_P0_schema_V0.3.sql` | 不变 |
| `docs/ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_README_V0.3.md` | `docs/design/ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_README_V0.3.md` | 不变 |
| `docs/ServiceFlow_AI_Design_V0.3.1.md` | `docs/design/ServiceFlow_AI_Design_V0.3.1.md` | 不变 |
| `docs/ServiceFlow_AI_Ollama_Call_Verification_2026-10-20.md` | `docs/verification/ServiceFlow_AI_Ollama_Call_Verification_2026-10-20.md` | 仅当前引用/注释更新；原文已留档 |
| `docs/ServiceFlow_AI_P0_patch_V0.3_to_V0.3.1.sql` | `docs/design/ServiceFlow_AI_P0_patch_V0.3_to_V0.3.1.sql` | 不变 |
| `docs/ServiceFlow_AI_P0_schema_V0.3.1.sql` | `docs/design/ServiceFlow_AI_P0_schema_V0.3.1.sql` | 不变 |
| `docs/ServiceFlow_AI_S1_AI_Hybrid_2026-10-20.md` | `docs/verification/ServiceFlow_AI_S1_AI_Hybrid_2026-10-20.md` | 仅当前引用/注释更新；原文已留档 |
| `docs/ServiceFlow_AI_S1_AI_Implementation.md` | `docs/implementation/ServiceFlow_AI_S1_AI_Implementation.md` | 仅当前引用/注释更新；原文已留档 |
| `docs/ServiceFlow_AI_S1_AI_Refinement_2026-10-20.md` | `docs/verification/ServiceFlow_AI_S1_AI_Refinement_2026-10-20.md` | 仅当前引用/注释更新；原文已留档 |
| `docs/ServiceFlow_AI_S1_AI_Validation_V0.3.1.md` | `docs/verification/ServiceFlow_AI_S1_AI_Validation_V0.3.1.md` | 仅当前引用/注释更新；原文已留档 |
| `docs/ServiceFlow_AI_S1_Implementation.md` | `docs/implementation/ServiceFlow_AI_S1_Implementation.md` | 仅当前引用/注释更新；原文已留档 |
| `docs/ServiceFlow_AI_S2_First_Segment_2026-10-20.md` | `docs/implementation/ServiceFlow_AI_S2_First_Segment_2026-10-20.md` | 仅当前引用/注释更新；原文已留档 |
| `config_al/model.local.properties` | `experiments/s1-ai/config/model.local.properties` | 不变 |
| `experiments/s1-ai/model.example.properties` | `experiments/s1-ai/config/model.example.properties` | 仅当前引用/注释更新；原文已留档 |
| `experiments/s1-ai/data/dev.jsonl` | `experiments/s1-ai/datasets/dev.jsonl` | 不变 |
| `experiments/s1-ai/data/fixed.jsonl` | `experiments/s1-ai/datasets/fixed.jsonl` | 不变 |
| `experiments/s1-ai/data/knowledge.jsonl` | `experiments/s1-ai/knowledge/knowledge.jsonl` | 不变 |
| `artifacts/freezes/a3-v01-2026-10-20.jar` | `experiments/s1-ai/freezes/a3-v01-2026-10-20/runtime.jar` | 不变 |
| `artifacts/development/a3-20261020/before/backend/scripts/Run-S1Ai.ps1` | `docs/verification/local-history/development/a3-20261020/before/backend/scripts/Run-S1Ai.ps1` | 不变 |
| `artifacts/development/a3-20261020/before/backend/src/main/java/com/serviceflow/experiment/ExperimentCli.java` | `docs/verification/local-history/development/a3-20261020/before/backend/src/main/java/com/serviceflow/experiment/ExperimentCli.java` | 不变 |
| `artifacts/development/a3-20261020/before/backend/src/main/java/com/serviceflow/experiment/ExperimentMetrics.java` | `docs/verification/local-history/development/a3-20261020/before/backend/src/main/java/com/serviceflow/experiment/ExperimentMetrics.java` | 不变 |
| `artifacts/development/a3-20261020/before/backend/src/main/java/com/serviceflow/experiment/ModelClient.java` | `docs/verification/local-history/development/a3-20261020/before/backend/src/main/java/com/serviceflow/experiment/ModelClient.java` | 不变 |
| `artifacts/development/a3-20261020/before/docs/README.md` | `docs/verification/local-history/development/a3-20261020/before/docs/README.md` | 不变 |
| `artifacts/development/a3-20261020/before/docs/ServiceFlow_AI_S1_AI_Implementation.md` | `docs/verification/local-history/development/a3-20261020/before/docs/ServiceFlow_AI_S1_AI_Implementation.md` | 不变 |
| `artifacts/development/a3-20261020/before/experiments/s1-ai/evidence/README.md` | `docs/verification/local-history/development/a3-20261020/before/experiments/s1-ai/evidence/README.md` | 不变 |
| `artifacts/development/a3-20261020/before/experiments/s1-ai/README.md` | `docs/verification/local-history/development/a3-20261020/before/experiments/s1-ai/README.md` | 不变 |
| `artifacts/development/a3-20261020/data-hashes-before.json` | `docs/verification/local-history/development/a3-20261020/data-hashes-before.json` | 不变 |
| `artifacts/development/a3-20261020/dev-run.log` | `docs/verification/local-history/development/a3-20261020/dev-run.log` | 不变 |
| `artifacts/development/a3-20261020/final-checks.json` | `docs/verification/local-history/development/a3-20261020/final-checks.json` | 不变 |
| `artifacts/development/a3-20261020/tests.log` | `docs/verification/local-history/development/a3-20261020/tests.log` | 不变 |
| `artifacts/development/s2-20261020/before/backend/src/main/java/com/serviceflow/ticket/TicketMapper.java` | `docs/verification/local-history/development/s2-20261020/before/backend/src/main/java/com/serviceflow/ticket/TicketMapper.java` | 不变 |
| `artifacts/development/s2-20261020/before/docs/README.md` | `docs/verification/local-history/development/s2-20261020/before/docs/README.md` | 不变 |
| `artifacts/development/s2-20261020/before/experiments/s1-ai/README.md` | `docs/verification/local-history/development/s2-20261020/before/experiments/s1-ai/README.md` | 不变 |
| `artifacts/development/s2-20261020/before/experiments/s1-ai/review/README.md` | `docs/verification/local-history/development/s2-20261020/before/experiments/s1-ai/review/README.md` | 不变 |
| `artifacts/development/s2-20261020/before/README.md` | `docs/verification/local-history/development/s2-20261020/before/README.md` | 不变 |
| `artifacts/development/s2-20261020/evidence-review-tool-tests/checks.json` | `docs/verification/local-history/development/s2-20261020/evidence-review-tool-tests/checks.json` | 不变 |
| `artifacts/development/s2-20261020/evidence-review-tool-tests/reject_invalid_enum.jsonl` | `docs/verification/local-history/development/s2-20261020/evidence-review-tool-tests/reject_invalid_enum.jsonl` | 不变 |
| `artifacts/development/s2-20261020/evidence-review-tool-tests/reject_material_change.jsonl` | `docs/verification/local-history/development/s2-20261020/evidence-review-tool-tests/reject_material_change.jsonl` | 不变 |
| `artifacts/development/s2-20261020/evidence-review-tool-tests/reject_missing_reviewer.jsonl` | `docs/verification/local-history/development/s2-20261020/evidence-review-tool-tests/reject_missing_reviewer.jsonl` | 不变 |
| `artifacts/development/s2-20261020/evidence-review-tool-tests/reject_numeric_boolean.jsonl` | `docs/verification/local-history/development/s2-20261020/evidence-review-tool-tests/reject_numeric_boolean.jsonl` | 不变 |
| `artifacts/development/s2-20261020/evidence-review-tool-tests/reject_unknown_id.jsonl` | `docs/verification/local-history/development/s2-20261020/evidence-review-tool-tests/reject_unknown_id.jsonl` | 不变 |
| `artifacts/development/s2-20261020/evidence-review-tool-tests/synthetic-summary.json` | `docs/verification/local-history/development/s2-20261020/evidence-review-tool-tests/synthetic-summary.json` | 不变 |
| `artifacts/development/s2-20261020/evidence-review-tool-tests/synthetic_valid_rating.jsonl` | `docs/verification/local-history/development/s2-20261020/evidence-review-tool-tests/synthetic_valid_rating.jsonl` | 不变 |
| `artifacts/development/s2-20261020/final-checks.json` | `docs/verification/local-history/development/s2-20261020/final-checks.json` | 不变 |
| `artifacts/development/s2-20261020/preserved-hashes.json` | `docs/verification/local-history/development/s2-20261020/preserved-hashes.json` | 不变 |
| `artifacts/development/s2-20261020/tests-first.log` | `docs/verification/local-history/development/s2-20261020/tests-first.log` | 不变 |
