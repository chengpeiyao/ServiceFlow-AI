# 开发需求与数据库契约

- [开发需求规格说明书](./ServiceFlow_AI_Requirements.md)：唯一持续维护的需求基线，覆盖 P0 业务、权限、状态、SLA、AI、接口、页面、数据和验收。
- [目标架构提案](./ServiceFlow_AI_Target_Architecture.md)：多租户、多 Agent 目标架构（v0.2，飞书暂缓），**待确认**；确认前规格书和 AGENTS.md 的现行约束仍然有效，确认后需求性内容并入规格书。
- [现行 SQL 基线](./ServiceFlow_AI_Design_Package_V3.1/ServiceFlow_AI_P0_schema_V0.3.1.sql)：保留原文件名；运行工程使用 `backend/src/main/resources/db/migration` 下的 Flyway 迁移。
- [V4 运行增量](../../backend/src/main/resources/db/migration/V4__resolution_cycles_and_sla_precision.sql)：任务解决轮次、整改来源及 SLA 累计毫秒；历史 SQL 快照不随增量改写。
- [历史 V0.3 → V0.3.1 结构增量](./ServiceFlow_AI_Design_Package_V3.1/ServiceFlow_AI_P0_patch_V0.3_to_V0.3.1.sql)：仅作已建测试库的结构演进参考，不是生产数据迁移方案。

旧版设计正文和旧设计包导航已整合移除，后续直接更新规格书，不再新增并行设计版本。历史 [V0.2 SQL](./ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_P0_schema_V0.2.sql) 和 [V0.3 SQL](./ServiceFlow_AI_Design_Package_V0.3/ServiceFlow_AI_P0_schema_V0.3.sql) 保留用于追溯，不作为当前业务需求或直接部署入口。

当前完成状态见 [交接状态](../handoff/CURRENT.md)，原始验证与实验材料仍由各自导航维护。返回 [文档导航](../README.md)。
