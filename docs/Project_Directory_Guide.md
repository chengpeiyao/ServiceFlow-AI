# 项目目录与文件归属

本轮仅规范化目录和引用，不改变业务逻辑、SQL、Prompt、数据标签或评测口径。[完整目录树与移动清单](./verification/workspace-layout-2026-10-20/README.md) 保留每个文件的旧路径、新路径和内容摘要。

| 路径 | 归属 |
|---|---|
| `.vscode` | 当前工作区 Maven、JDK 配置，保持位置 |
| `backend` | Java 主代码、资源、SQL、测试、pom.xml |
| `backend/scripts` | 后端隔离 MySQL 测试及 Java 实验入口脚本 |
| `backend/.local/tmp` | 项目测试的 MySQL/JVM 临时目录，被 Git 忽略 |
| `docs/design` | 统一开发需求规格、数据库契约及历史 SQL 参考 |
| `docs/implementation` | S1、S1-AI、S2 首段实施记录 |
| `docs/verification` | 验证计划、分析报告、稳定测试记录 |
| `docs/verification/local-history` | 原样保存的本机历史验证材料与变更前快照，被 Git 忽略 |
| `experiments/s1-ai/config` | 唯一的实验模型配置来源及模板，本机配置不提交 |
| `experiments/s1-ai/datasets` | 开发集和固定检查集，内容保持不变 |
| `experiments/s1-ai/knowledge` | 知识语料，内容保持不变 |
| `experiments/s1-ai/prompts` | 内嵌 Java Prompt 的版本与源码索引，无第二份可执行 Prompt |
| `experiments/s1-ai/review` | 人工标签和语义评分材料及工具 |
| `experiments/s1-ai/evidence` | 不可改写的原始实验结果与 manifest |
| `experiments/s1-ai/freezes` | A3 原始源码/证据快照及原运行 JAR |
| `scripts` | 项目级测试与构建协调脚本 |
| `tools` | 本机 JDK、独立 Maven settings 和离线缓存，保持现状 |
| `artifacts/runtime/ollama` | 已运行 Ollama 进程的日志和状态，保留原位置避免中断服务 |

根目录原来实际存在的是 `config_al`，不是 `config_ai`。其中唯一的模型配置已原样移入实验 config，Java 后端应用配置仍使用 backend/src/main/resources/application*.yml，不合并为同一配置来源。未创建 frontend 或其他无用模块。

## 开发入口

在项目根目录运行现有入口：

```powershell
.\scripts\Test-Project.ps1 -Offline
```

入口仍使用项目 JDK 21、Maven 缓存及隔离 MySQL；临时文件改放 backend/.local/tmp，避免污染 C 盘，也避免被 Maven clean 删除正在使用的 MySQL 数据目录。构建输出仍在 backend/target，不移到历史证据目录。

模型实验仍从项目根目录调用 backend/scripts/Run-S1Ai.ps1；默认配置路径已更新为 experiments/s1-ai/config/model.local.properties，模型、接口、参数和密钥环境变量保持原内容。本轮不启动真实模型实验或固定检查集评测。

## 历史路径与完整性

历史 manifest、JSON、XML、日志和压缩快照不重写旧路径。使用 [逐文件映射](./verification/workspace-layout-2026-10-20/relocations.json) 查找新位置，再按原摘要验证。冻结源码和运行 JAR 与当前新增路径适配后的构建不是同一实物，复现原轮次应使用冻结快照及原 JAR。

本轮保留原有未提交改动；已跟踪文件用 git mv 移动，未执行提交、重置或清空。受影响文档的整理前原文、原 Git 差异和索引清单保存在本轮验证目录的 .local 中并被忽略。历史回退快照是只读资料，不能直接执行其中的旧同步脚本。

此前已经缺失的 V0.1、artifacts/migration-2026-10-20 和 artifacts/workspaces 不在本轮可迁移文件中，不补造、不声称已恢复。返回 [文档导航](./README.md)。
