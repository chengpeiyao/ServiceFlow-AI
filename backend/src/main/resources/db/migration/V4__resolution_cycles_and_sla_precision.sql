-- 首轮任务沿用轮次 1；整改来源由拒绝验收事务写入，保留历史任务。
ALTER TABLE ticket_resolution ADD UNIQUE KEY uk_resolution_ticket (id, ticket_id);
ALTER TABLE service_task
    ADD COLUMN resolution_cycle_no INT NOT NULL DEFAULT 1,
    ADD COLUMN source_resolution_id BIGINT NULL,
    ADD UNIQUE KEY uk_task_source_resolution (source_resolution_id),
    ADD CONSTRAINT fk_task_source_resolution FOREIGN KEY (source_resolution_id, ticket_id)
        REFERENCES ticket_resolution (id, ticket_id),
    ADD CONSTRAINT chk_task_resolution_cycle CHECK (
        (resolution_cycle_no = 1 AND source_resolution_id IS NULL)
        OR (resolution_cycle_no > 1 AND source_resolution_id IS NOT NULL));

-- 毫秒为权威累计值；秒字段只作为总毫秒向下取整的兼容投影。
ALTER TABLE ticket_sla_instance
    ADD COLUMN resolution_elapsed_millis BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_sla_elapsed_millis CHECK (resolution_elapsed_millis >= 0);
UPDATE ticket_sla_instance SET resolution_elapsed_millis = resolution_elapsed_seconds * 1000;
