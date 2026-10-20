# 验证报告与证据导航

- [S1 与 AI 验证计划](./ServiceFlow_AI_S1_AI_Validation_V0.3.1.md)
- [本地模型调用验证](./ServiceFlow_AI_Ollama_Call_Verification_2026-10-20.md)
- [开发集完善与效果分析](./ServiceFlow_AI_S1_AI_Refinement_2026-10-20.md)
- [A3 混合受理与标签复核准备](./ServiceFlow_AI_S1_AI_Hybrid_2026-10-20.md)
- [原 S2 90 项测试记录](./s2-first-2026-10-20/summary.json)
- [此前文件清理记录](./workspace-cleanup-2026-10-20.json)
- [本轮目录规范化报告](./workspace-layout-2026-10-20/README.md)
- [模型原始证据导航](../../experiments/s1-ai/evidence/README.md)
- [A3 冻结快照](../../experiments/s1-ai/freezes/a3-v01-2026-10-20/README.md)
- [人工证据评分说明](../../experiments/s1-ai/review/Evidence_Review_Guide.md)

`local-history/development` 原样保存从 artifacts 迁来的验证日志、检查结果、评分工具自测输入以及变更前快照。它被 Git 忽略，包含历史绝对路径的脚本不应作为当前执行入口。历史 JSON/XML/日志/manifest 保持原字节内容，旧路径使用 [迁移映射](./workspace-layout-2026-10-20/relocations.json) 定位，不覆盖历史摘要。

返回 [文档导航](../README.md)。
