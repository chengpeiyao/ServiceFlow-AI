-- 仅在 dev 与独立测试库加载，沿用公开演示口令；不修改已执行的 V2 校验和。
INSERT INTO app_user(id,username,display_name,password_hash,role_code,account_status,created_at)
SELECT 8,'engineer_backup','数据库备用工程师',password_hash,'ENGINEER','ACTIVE',created_at FROM app_user WHERE id=5;
INSERT INTO app_user(id,username,display_name,password_hash,role_code,account_status,created_at)
SELECT 9,'engineer_other','应用团队工程师',password_hash,'ENGINEER','ACTIVE',created_at FROM app_user WHERE id=5;
INSERT INTO app_user(id,username,display_name,password_hash,role_code,account_status,created_at)
SELECT 10,'manager_backup','数据库团队备用主管',password_hash,'SERVICE_MANAGER','ACTIVE',created_at FROM app_user WHERE id=4;
INSERT INTO team_member(team_id,user_id) VALUES(1,8),(2,9);
INSERT INTO team_manager_scope(team_id,manager_user_id) VALUES(1,10);
