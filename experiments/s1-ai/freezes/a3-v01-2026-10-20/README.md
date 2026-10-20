# A3 v0.1 冻结记录

A3 保留为实验候选，不接入 P0 业务；后续 Java S2 开发独立推进。

`snapshot.zip` 保留实验源码、测试、运行脚本、原始数据/Schema、报告和完整 A3 证据，逐文件 SHA-256 见 `manifest.json`。原运行 JAR 保存于项目 `experiments/s1-ai/freezes/a3-v01-2026-10-20/runtime.jar`，与已归档环境记录的摘要一致。JAR 被 Git 忽略，需另行备份；源码/证据快照可版本管理。

原有 65 项测试记录属于冻结时基线。人工语义评分未完成，40 条固定案例未调用模型，不能把冻结版本当作效果验收通过。禁止覆盖本目录；将来任何模型、Prompt、标签或任务定义变更应另建版本。快照中的脚本和路径为历史记录，不应直接覆盖当前业务工程。

## 目录迁移说明

原运行 JAR 现为本目录 runtime.jar。原 manifest 的 jar 路径和 files 键保持原样，使用 [目录映射](../../../../docs/verification/workspace-layout-2026-10-20/relocations.json) 定位。snapshot.zip 的内容和摘要均未变；其中源码属于冻结时版本，当前路径适配后的源码摘要可能不同，不能冒充原实验构建。
