-- ServiceFlow AI / V0.3.1 P0 initial schema (consolidated draft)
-- MySQL 8.0.16+ / InnoDB / utf8mb4 / application stores UTC in DATETIME(3).
-- User reported original V0.3 schema passed MySQL 8.0.46 DDL/constraint checks.
-- V0.3.1 adds two nullable ticket fields; THESE CHANGES have not been DB-retested.
-- This is a NEW DATABASE bootstrap candidate, NOT a migration of live V0.2 data.
-- P0 excludes entitlement exception approval, WAITING_CUSTOMER, parallel blocking tasks,
-- AI direct assignment, generic agent execution, and configurable workday calendars.

CREATE TABLE app_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    account_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_user_username (username),
    CONSTRAINT chk_user_role CHECK (role_code IN (
        'CUSTOMER_CONTACT', 'SUPPORT_AGENT', 'SERVICE_MANAGER', 'ENGINEER', 'OPS_ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE customer (
    id BIGINT PRIMARY KEY,
    customer_code VARCHAR(64) NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    UNIQUE KEY uk_customer_code (customer_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE customer_user (
    customer_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (customer_id, user_id),
    KEY idx_customer_user_user (user_id),
    CONSTRAINT fk_cu_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_cu_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE service_team (
    id BIGINT PRIMARY KEY,
    team_code VARCHAR(64) NOT NULL,
    team_name VARCHAR(120) NOT NULL,
    UNIQUE KEY uk_team_code (team_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE team_member (
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (team_id, user_id),
    KEY idx_team_member_user (user_id),
    CONSTRAINT fk_tm_team FOREIGN KEY (team_id) REFERENCES service_team(id),
    CONSTRAINT fk_tm_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE team_manager_scope (
    team_id BIGINT NOT NULL,
    manager_user_id BIGINT NOT NULL,
    PRIMARY KEY (team_id, manager_user_id),
    KEY idx_tms_manager (manager_user_id),
    CONSTRAINT fk_tms_team FOREIGN KEY (team_id) REFERENCES service_team(id),
    CONSTRAINT fk_tms_manager FOREIGN KEY (manager_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE service_type_routing (
    service_type VARCHAR(64) PRIMARY KEY,
    owner_team_id BIGINT NOT NULL,
    routing_version INT NOT NULL DEFAULT 1,
    CONSTRAINT chk_routing_version CHECK (routing_version >= 1),
    CONSTRAINT fk_routing_team FOREIGN KEY (owner_team_id) REFERENCES service_team(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE service_contract (
    id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    contract_code VARCHAR(80) NOT NULL,
    service_tier VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    valid_from DATETIME(3) NOT NULL,
    valid_until DATETIME(3) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_contract_code (contract_code),
    UNIQUE KEY uk_contract_customer (id, customer_id),
    KEY idx_contract_customer_status (customer_id, status),
    CONSTRAINT chk_contract_interval CHECK (valid_until > valid_from),
    CONSTRAINT fk_contract_customer FOREIGN KEY (customer_id) REFERENCES customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contract_entitlement (
    contract_id BIGINT NOT NULL,
    service_type VARCHAR(64) NOT NULL,
    is_enabled TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (contract_id, service_type),
    CONSTRAINT chk_entitlement_enabled CHECK (is_enabled IN (0, 1)),
    CONSTRAINT fk_ent_contract FOREIGN KEY (contract_id) REFERENCES service_contract(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sla_policy_version (
    id BIGINT PRIMARY KEY,
    policy_code VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    service_tier VARCHAR(40) NOT NULL,
    service_type VARCHAR(64) NOT NULL,
    priority VARCHAR(24) NOT NULL,
    calendar_mode VARCHAR(24) NOT NULL DEFAULT 'ALWAYS_ON',
    response_target_minutes INT NOT NULL,
    resolution_target_minutes INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_sla_policy_code_version (policy_code, version_no),
    UNIQUE KEY uk_sla_policy_id_dims (id, service_tier, service_type, priority),
    CONSTRAINT chk_sla_positive CHECK (
        response_target_minutes > 0 AND resolution_target_minutes > 0),
    CONSTRAINT chk_sla_calendar_p0 CHECK (calendar_mode = 'ALWAYS_ON'),
    CONSTRAINT chk_sla_priority CHECK (priority IN ('P1', 'P2', 'P3'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Current-version pointer is unique for each (tier, service_type, priority).
-- Referenced sla_policy_version rows are immutable after publication.
CREATE TABLE sla_policy_binding (
    service_tier VARCHAR(40) NOT NULL,
    service_type VARCHAR(64) NOT NULL,
    priority VARCHAR(24) NOT NULL,
    active_policy_version_id BIGINT NOT NULL,
    PRIMARY KEY (service_tier, service_type, priority),
    CONSTRAINT fk_sla_binding_version_dims FOREIGN KEY (
        active_policy_version_id, service_tier, service_type, priority)
        REFERENCES sla_policy_version(id, service_tier, service_type, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE service_ticket (
    id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    creator_user_id BIGINT NOT NULL,
    owner_team_id BIGINT NOT NULL,
    routing_version INT NOT NULL,
    title VARCHAR(240) NOT NULL,
    original_description TEXT NOT NULL COMMENT 'Immutable customer-authored text',
    reported_product_code VARCHAR(80) NULL COMMENT 'Explicit customer-reported code; not model-inferred',
    reported_product_version VARCHAR(80) NULL COMMENT 'Explicit customer-reported version; may be unknown',
    service_type VARCHAR(64) NOT NULL COMMENT 'Determines entitlement, routing and SLA; immutable in P0',
    category VARCHAR(64) NULL COMMENT 'Human-confirmed diagnostic classification only',
    impact_level VARCHAR(24) NOT NULL,
    urgency_level VARCHAR(24) NOT NULL,
    priority VARCHAR(24) NOT NULL COMMENT 'Set by deterministic priority matrix',
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    resolution_cycle_no INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    closed_at DATETIME(3) NULL,
    UNIQUE KEY uk_ticket_id_customer (id, customer_id),
    KEY idx_ticket_customer_created (customer_id, created_at),
    KEY idx_ticket_team_status (owner_team_id, status),
    KEY idx_ticket_status_updated (status, updated_at),
    CONSTRAINT chk_ticket_status CHECK (status IN (
        'SUBMITTED', 'TRIAGED', 'ASSIGNED', 'IN_PROGRESS',
        'PENDING_ACCEPTANCE', 'CLOSED', 'CANCELLED')),
    CONSTRAINT chk_ticket_priority CHECK (priority IN ('P1', 'P2', 'P3')),
    CONSTRAINT chk_ticket_cycle CHECK (resolution_cycle_no >= 0),
    CONSTRAINT fk_ticket_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_ticket_contract_customer FOREIGN KEY (contract_id, customer_id)
        REFERENCES service_contract(id, customer_id),
    CONSTRAINT fk_ticket_creator FOREIGN KEY (creator_user_id) REFERENCES app_user(id),
    CONSTRAINT fk_ticket_team FOREIGN KEY (owner_team_id) REFERENCES service_team(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_sla_instance (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    policy_version_id BIGINT NOT NULL,
    effective_start_at DATETIME(3) NOT NULL,
    response_due_at DATETIME(3) NOT NULL,
    resolution_due_at DATETIME(3) NULL,
    first_responded_at DATETIME(3) NULL,
    response_met TINYINT NULL,
    resolution_met TINYINT NULL,
    resolution_clock_state VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    resolution_elapsed_seconds BIGINT NOT NULL DEFAULT 0,
    resolution_last_started_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_sla_ticket (ticket_id),
    CONSTRAINT chk_sla_elapsed CHECK (resolution_elapsed_seconds >= 0),
    CONSTRAINT chk_sla_clock CHECK (resolution_clock_state IN (
        'RUNNING', 'PAUSED_ACCEPTANCE', 'CLOSED', 'CANCELLED')),
    CONSTRAINT fk_sla_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_sla_policy FOREIGN KEY (policy_version_id) REFERENCES sla_policy_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_sla_event (
    id BIGINT PRIMARY KEY,
    sla_instance_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    event_at DATETIME(3) NOT NULL,
    command_key VARCHAR(128) NOT NULL,
    actor_user_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    UNIQUE KEY uk_sla_sequence (sla_instance_id, sequence_no),
    UNIQUE KEY uk_sla_command (sla_instance_id, command_key),
    CONSTRAINT fk_sla_event_instance FOREIGN KEY (sla_instance_id) REFERENCES ticket_sla_instance(id),
    CONSTRAINT fk_sla_event_actor FOREIGN KEY (actor_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- P0: at most ONE active blocking task per ticket.
-- MySQL UNIQUE permits multiple NULL values: completed/cancelled rows do not occupy slot.
-- All writers must ALSO lock service_ticket first and check ticket status after locking.
CREATE TABLE service_task (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    title VARCHAR(240) NOT NULL,
    is_blocking TINYINT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    current_assignee_id BIGINT NULL,
    version INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    active_blocking_ticket_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN is_blocking = 1 AND status IN (
            'CREATED', 'ASSIGNED', 'ACCEPTED', 'PROCESSING')
            THEN ticket_id ELSE NULL END
    ) STORED,
    UNIQUE KEY uk_one_active_blocking_task (active_blocking_ticket_id),
    KEY idx_task_ticket_status (ticket_id, status),
    KEY idx_task_assignee_status (current_assignee_id, status),
    CONSTRAINT chk_task_status CHECK (status IN (
        'CREATED', 'ASSIGNED', 'ACCEPTED', 'PROCESSING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_task_blocking_p0 CHECK (is_blocking = 1),
    CONSTRAINT fk_task_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_task_assignee FOREIGN KEY (current_assignee_id) REFERENCES app_user(id),
    CONSTRAINT fk_task_creator FOREIGN KEY (created_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_assignment_history (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    previous_assignee_id BIGINT NULL,
    new_assignee_id BIGINT NOT NULL,
    assigned_by BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    assigned_at DATETIME(3) NOT NULL,
    KEY idx_assignment_task_time (task_id, assigned_at),
    CONSTRAINT fk_assign_task FOREIGN KEY (task_id) REFERENCES service_task(id),
    CONSTRAINT fk_assign_prev FOREIGN KEY (previous_assignee_id) REFERENCES app_user(id),
    CONSTRAINT fk_assign_new FOREIGN KEY (new_assignee_id) REFERENCES app_user(id),
    CONSTRAINT fk_assign_actor FOREIGN KEY (assigned_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_activity (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    task_id BIGINT NULL,
    actor_user_id BIGINT NOT NULL,
    activity_type VARCHAR(40) NOT NULL,
    body TEXT NOT NULL,
    customer_visible TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    KEY idx_activity_ticket_time (ticket_id, created_at),
    CONSTRAINT fk_activity_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_activity_task FOREIGN KEY (task_id) REFERENCES service_task(id),
    CONSTRAINT fk_activity_actor FOREIGN KEY (actor_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_resolution (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    cycle_no INT NOT NULL,
    summary TEXT NOT NULL,
    submitted_by BIGINT NOT NULL,
    submitted_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_resolution_cycle (ticket_id, cycle_no),
    UNIQUE KEY uk_resolution_composite (id, ticket_id, cycle_no),
    CONSTRAINT fk_resolution_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_resolution_actor FOREIGN KEY (submitted_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_acceptance (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    resolution_id BIGINT NOT NULL,
    cycle_no INT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    decided_by BIGINT NOT NULL,
    reason VARCHAR(1000) NULL,
    decided_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_acceptance_resolution (resolution_id),
    UNIQUE KEY uk_acceptance_cycle (ticket_id, cycle_no),
    CONSTRAINT chk_acceptance_decision CHECK (decision IN ('ACCEPT', 'REJECT')),
    CONSTRAINT fk_acceptance_resolution_tuple FOREIGN KEY (resolution_id, ticket_id, cycle_no)
        REFERENCES ticket_resolution(id, ticket_id, cycle_no),
    CONSTRAINT fk_acceptance_actor FOREIGN KEY (decided_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_article_version (
    id BIGINT PRIMARY KEY,
    article_code VARCHAR(80) NOT NULL,
    version_no INT NOT NULL,
    product_code VARCHAR(80) NOT NULL,
    applicable_versions VARCHAR(160) NOT NULL,
    title VARCHAR(240) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    preconditions_json JSON NOT NULL,
    tags_json JSON NOT NULL,
    status VARCHAR(24) NOT NULL,
    approved_by BIGINT NULL,
    approved_at DATETIME(3) NULL,
    UNIQUE KEY uk_kb_article_version (article_code, version_no),
    KEY idx_kb_product_status (product_code, status),
    CONSTRAINT chk_kb_status CHECK (status IN ('DRAFT', 'APPROVED', 'RETIRED')),
    CONSTRAINT fk_kb_approver FOREIGN KEY (approved_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_triage_run (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    generation_key VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64) NOT NULL COMMENT 'Canonical request digest for generation-key replay',
    observed_ticket_version INT NOT NULL,
    observed_description_sha256 CHAR(64) NOT NULL,
    context_sha256 CHAR(64) NOT NULL,
    knowledge_set_hash CHAR(64) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    model_config_sha256 CHAR(64) NOT NULL,
    run_status VARCHAR(32) NOT NULL,
    retrieved_evidence_json JSON NULL,
    validated_output_json JSON NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    latency_ms BIGINT NULL,
    failure_code VARCHAR(80) NULL,
    created_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    UNIQUE KEY uk_triage_generation (ticket_id, requested_by, generation_key),
    KEY idx_triage_run_ticket_time (ticket_id, created_at),
    CONSTRAINT chk_triage_run_status CHECK (run_status IN (
        'QUEUED', 'RUNNING', 'COMPLETED', 'STALE', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT fk_triage_run_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_triage_run_actor FOREIGN KEY (requested_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_triage_proposal (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    expected_ticket_version INT NOT NULL,
    proposed_category VARCHAR(64) NULL,
    extracted_facts_json JSON NOT NULL,
    missing_info_json JSON NOT NULL,
    followup_questions_json JSON NOT NULL,
    diagnostic_suggestions_json JSON NOT NULL,
    proposal_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
    expires_at DATETIME(3) NOT NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    open_review_ticket_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN proposal_status = 'PENDING_REVIEW' THEN ticket_id ELSE NULL END
    ) STORED,
    UNIQUE KEY uk_proposal_run (run_id),
    UNIQUE KEY uk_one_pending_triage (open_review_ticket_id),
    KEY idx_proposal_ticket_status (ticket_id, proposal_status),
    CONSTRAINT chk_triage_proposal_status CHECK (proposal_status IN (
        'PENDING_REVIEW', 'APPLIED', 'REJECTED', 'STALE', 'EXPIRED')),
    CONSTRAINT fk_triage_proposal_run FOREIGN KEY (run_id) REFERENCES ai_triage_run(id),
    CONSTRAINT fk_triage_proposal_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_triage_proposal_reviewer FOREIGN KEY (reviewed_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_triage_record (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    proposal_id BIGINT NULL COMMENT 'NULL means fully manual triage',
    confirmed_category VARCHAR(64) NOT NULL,
    confirmed_facts_json JSON NOT NULL,
    confirmed_followups_json JSON NOT NULL,
    confirmed_diagnostics_json JSON NOT NULL,
    reviewed_by BIGINT NOT NULL,
    applied_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_triage_record_ticket (ticket_id),
    UNIQUE KEY uk_triage_record_proposal (proposal_id),
    CONSTRAINT fk_triage_record_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_triage_record_proposal FOREIGN KEY (proposal_id) REFERENCES ai_triage_proposal(id),
    CONSTRAINT fk_triage_record_actor FOREIGN KEY (reviewed_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE business_audit_log (
    id BIGINT PRIMARY KEY,
    actor_user_id BIGINT NULL,
    actor_kind VARCHAR(24) NOT NULL,
    customer_id BIGINT NULL,
    ticket_id BIGINT NULL,
    action_code VARCHAR(80) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id BIGINT NOT NULL,
    request_id VARCHAR(80) NULL,
    detail_json JSON NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    KEY idx_audit_ticket_time (ticket_id, occurred_at),
    KEY idx_audit_resource (resource_type, resource_id),
    CONSTRAINT chk_audit_actor_kind CHECK (actor_kind IN ('HUMAN', 'AI_ASSISTED', 'SYSTEM')),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES app_user(id),
    CONSTRAINT fk_audit_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_audit_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE idempotency_request (
    id BIGINT PRIMARY KEY,
    actor_user_id BIGINT NOT NULL,
    action_code VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    request_status VARCHAR(24) NOT NULL,
    response_http_status SMALLINT NULL,
    response_body_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uk_idem_actor_action_key (actor_user_id, action_code, idempotency_key),
    CONSTRAINT chk_idem_status CHECK (request_status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT fk_idem_actor FOREIGN KEY (actor_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- INVARIANTS ENFORCED IN JAVA TRANSACTIONAL DOMAIN SERVICES (not solely via FK):
-- 1. Every ticket mutation locks service_ticket by PK FIRST; task rows by ascending PK next.
-- 2. Any aggregate-impacting task change increments service_ticket.version.
-- 3. Original description, service_type, initial priority, owner_team_id and SLA link
--    are immutable under P0 operations. Triage edits category and confirmed triage record ONLY.
-- 4. No model call, HTTP request, or long computation occurs while DB locks are held.
-- 5. ticket.owner_team_id resolved via service_type_routing at creation and then frozen.
-- 6. Manager must hold team_manager_scope and assignee must be in team_member.
-- 7. kb article approved content is immutable; corrections create a new version.
-- 8. ai_triage_proposal expected_ticket_version equals observed version in ai_triage_run;
--    run.ticket_id == proposal.ticket_id. Generated result may not be relabeled with a new version.
-- 9. One active blocking task also guarded by uk_one_active_blocking_task generated index;
--    MySQL allows multiple NULL values in UNIQUE, permitting many historical closed tasks.
-- 10. ai_triage_proposal has a max one pending-review suggestion per ticket (unique generated).
-- 11. service_ticket.status, service_task.status, resolution cycle and SLA clock state
--    must remain mutually consistent after every command.
-- 12. Knowledge citation referential validity and substantive support of any claim
--    are application/evaluation checks, not guaranteed by the JSON and foreign keys.
-- 13. Customer access requires membership and ticket creator for final acceptance.
-- 14. When a proposal is stale, allowed reviewer writes STALE + audit and returns a
--    normal business rejection response; unauthorized caller must not mutate anything.
-- 15. A reused generation_key with a different request_sha256 is a 409 conflict.
-- 16. AI model provider calls are NOT exactly-once, even if generation_key deduplicates
--    local requests; crashed calls can be billed again after a new explicit request.

-- V0.3.1 additional application invariants (not enforced by these nullable columns):
-- 17. reported_product_code must be in the seed-approved catalog; if unknown, keep NULL.
-- 18. Only explicit CUSTOMER_CONTACT inputs at creation or SUBMITTED customer message can
--     update reported product/version under ticket root lock, bump version, and append audit.
-- 19. AI-derived product/version candidates cannot become verified retrieval filters.
-- 20. Knowledge filters: GENERAL/ALL always allowed; product-specific knowledge requires
--     explicit code and either applicable_versions=ALL or an exact version-list match.
-- 21. Save new AI proposal under ticket lock: stale old pending -> STALE; else expired
--     old pending -> EXPIRED; else valid old pending -> new RUN SUPERSEDED, no proposal.
-- 22. Task transfer ASSIGNED/ACCEPTED/PROCESSING -> ASSIGNED, ticket status unchanged;
--     old engineer loses current-assignee permission; versions and history updated atomically.
