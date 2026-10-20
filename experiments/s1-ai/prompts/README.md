# Prompt 版本与源码索引

当前 Prompt 内嵌于 [ModelClient.java](../../../backend/src/main/java/com/serviceflow/experiment/ModelClient.java) 的请求构造方法中。本轮不抽取、不复制运行文本，不增加第二份 Prompt 来源，源码内容摘要保持不变。

- A1/A2：s1-ai-intake-v0.3，使用同一模板，仅提供的检索证据不同。
- A3：s1-ai-hybrid-v0.1，版本标识和规则计划仍由原 [HybridTriage.java](../../../backend/src/main/java/com/serviceflow/experiment/HybridTriage.java) 实现。
- 历史版本：以各次原始 manifest 中的 promptVersion 和实际加载类摘要为准；A3 原始源码可在 [冻结快照](../freezes/a3-v01-2026-10-20/README.md) 中取得，不改写版本标签。

返回 [实验说明](../README.md)。
