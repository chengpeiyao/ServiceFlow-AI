-- 仅由 dev 和测试配置加载；演示口令在 README 中公开，禁止用于生产。
INSERT INTO app_user(id,username,display_name,password_hash,role_code,account_status,created_at) VALUES
(1,'customer_a','客户甲','$2a$10$9PLTD.3RiFR9noRi/rMi/eouK/PjnO1rZ.D867KkrgZjA6lbuSGtW','CUSTOMER_CONTACT','ACTIVE','2026-01-01'),
(2,'customer_b','客户乙','$2a$10$9PLTD.3RiFR9noRi/rMi/eouK/PjnO1rZ.D867KkrgZjA6lbuSGtW','CUSTOMER_CONTACT','ACTIVE','2026-01-01'),
(3,'support','客服','$2a$10$9PLTD.3RiFR9noRi/rMi/eouK/PjnO1rZ.D867KkrgZjA6lbuSGtW','SUPPORT_AGENT','ACTIVE','2026-01-01'),
(4,'manager','数据库团队主管','$2a$10$9PLTD.3RiFR9noRi/rMi/eouK/PjnO1rZ.D867KkrgZjA6lbuSGtW','SERVICE_MANAGER','ACTIVE','2026-01-01'),
(5,'engineer','数据库工程师','$2a$10$9PLTD.3RiFR9noRi/rMi/eouK/PjnO1rZ.D867KkrgZjA6lbuSGtW','ENGINEER','ACTIVE','2026-01-01'),
(6,'ops','运营管理员','$2a$10$9PLTD.3RiFR9noRi/rMi/eouK/PjnO1rZ.D867KkrgZjA6lbuSGtW','OPS_ADMIN','ACTIVE','2026-01-01'),
(7,'manager_other','应用团队主管','$2a$10$9PLTD.3RiFR9noRi/rMi/eouK/PjnO1rZ.D867KkrgZjA6lbuSGtW','SERVICE_MANAGER','ACTIVE','2026-01-01');
INSERT INTO customer(id,customer_code,customer_name,status) VALUES (1,'A','演示客户甲','ACTIVE'),(2,'B','演示客户乙','ACTIVE');
INSERT INTO customer_user VALUES (1,1),(2,2);
INSERT INTO service_team VALUES (1,'DATABASE','数据库服务团队'),(2,'APPLICATION','应用服务团队');
INSERT INTO team_member VALUES (1,5);
INSERT INTO team_manager_scope VALUES (1,4),(2,7);
INSERT INTO service_type_routing VALUES ('DATABASE_SUPPORT',1,1),('APPLICATION_SUPPORT',2,1);
INSERT INTO service_contract(id,customer_id,contract_code,service_tier,status,valid_from,valid_until,version) VALUES
(1,1,'A-VALID','GOLD','ACTIVE','2026-01-01','2030-01-01',0),
(2,2,'B-VALID','GOLD','ACTIVE','2026-01-01','2030-01-01',0),
(3,1,'A-EXPIRED','GOLD','ACTIVE','2026-01-01','2026-01-02',0),
(4,1,'A-DISABLED','GOLD','DISABLED','2026-01-01','2030-01-01',0);
INSERT INTO contract_entitlement VALUES (1,'DATABASE_SUPPORT',1),(2,'DATABASE_SUPPORT',1),(2,'APPLICATION_SUPPORT',1),(3,'DATABASE_SUPPORT',1),(4,'DATABASE_SUPPORT',1);
INSERT INTO sla_policy_version(id,policy_code,version_no,service_tier,service_type,priority,calendar_mode,response_target_minutes,resolution_target_minutes,created_at) VALUES
(1,'DB-P1',1,'GOLD','DATABASE_SUPPORT','P1','ALWAYS_ON',60,240,'2026-01-01'),
(2,'DB-P2',1,'GOLD','DATABASE_SUPPORT','P2','ALWAYS_ON',120,480,'2026-01-01'),
(3,'DB-P3',1,'GOLD','DATABASE_SUPPORT','P3','ALWAYS_ON',240,960,'2026-01-01'),
(4,'APP-P1',1,'GOLD','APPLICATION_SUPPORT','P1','ALWAYS_ON',60,240,'2026-01-01'),
(5,'APP-P2',1,'GOLD','APPLICATION_SUPPORT','P2','ALWAYS_ON',120,480,'2026-01-01'),
(6,'APP-P3',1,'GOLD','APPLICATION_SUPPORT','P3','ALWAYS_ON',240,960,'2026-01-01'),
(7,'DB-P1',2,'GOLD','DATABASE_SUPPORT','P1','ALWAYS_ON',30,180,'2026-01-01');
INSERT INTO sla_policy_binding VALUES ('GOLD','DATABASE_SUPPORT','P1',1),('GOLD','DATABASE_SUPPORT','P2',2),('GOLD','DATABASE_SUPPORT','P3',3),('GOLD','APPLICATION_SUPPORT','P1',4),('GOLD','APPLICATION_SUPPORT','P2',5),('GOLD','APPLICATION_SUPPORT','P3',6);
INSERT INTO knowledge_article_version(id,article_code,version_no,product_code,applicable_versions,title,content,preconditions_json,tags_json,status,approved_by,approved_at) VALUES
(201,'GENERAL-CONNECTION',1,'GENERAL','ALL','连接超时通用排查','连接超时可能来自网络、服务不可用或连接池等待，需要结合日志判断。','[]','["连接超时"]','APPROVED',6,'2026-01-01'),
(202,'COMS-12',1,'COMS','1.2','COMS 1.2 排查示例','仅用于 COMS 1.2 演示，不能作为 11.2 的依据。','[]','["COMS"]','APPROVED',6,'2026-01-01'),
(203,'COMS-112',1,'COMS','11.2','COMS 11.2 排查示例','仅用于 COMS 11.2 演示。','[]','["COMS"]','APPROVED',6,'2026-01-01'),
(204,'IOT-ALL',1,'IOT','ALL','IoT 通用排查示例','仅适用于 IoT 演示。','[]','["IOT"]','APPROVED',6,'2026-01-01'),
(205,'DRAFT',1,'GENERAL','ALL','未审核示例','未审核知识不得作为受理依据。','[]','[]','DRAFT',NULL,NULL);
