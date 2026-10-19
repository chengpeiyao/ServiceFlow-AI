-- ServiceFlow AI V0.3 -> V0.3.1 / P0 development database ONLY.
-- V0.3 original 25-table bootstrap was reported verified by user on MySQL 8.0.46.
-- THIS PATCH HAS NOT BEEN EXECUTED OR TESTED HERE.
-- No production migration guarantees; back up data and test in a temporary DB first.
-- Adds two nullable columns only; application logic must validate catalog values.

ALTER TABLE service_ticket
    ADD COLUMN reported_product_code VARCHAR(80) NULL
        COMMENT 'Explicit customer-reported code; not model-inferred'
        AFTER original_description,
    ADD COLUMN reported_product_version VARCHAR(80) NULL
        COMMENT 'Explicit customer-reported version; may be unknown'
        AFTER reported_product_code;

-- Recheck: SHOW COLUMNS FROM service_ticket;
-- Re-run contract/permission and observed ticket version concurrency integration tests.
