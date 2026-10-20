-- ServiceFlow AI / P0 relational schema draft V0.2
-- Target: MySQL 8.0.16+ / InnoDB / utf8mb4.  Design artifact: NOT executed on a MySQL server.
-- Recommended Flyway migration name after review: V1__serviceflow_p0_core.sql
-- Store all DATETIME(3) in UTC at the application boundary; policy/calendar timezone uses IANA zones.
-- No demo passwords or production secrets belong in migrations.

CREATE TABLE app_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    user_kind VARCHAR(24) NOT NULL COMMENT 'CUSTOMER / STAFF',
    account_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE app_role (
    id BIGINT PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    UNIQUE KEY uk_app_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES app_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE customer (
    id BIGINT PRIMARY KEY,
    customer_code VARCHAR(64) NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_customer_code (customer_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE customer_user (
    customer_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (customer_id, user_id),
    KEY idx_customer_user_user (user_id),
    CONSTRAINT fk_customer_user_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_customer_user_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE service_team (
    id BIGINT PRIMARY KEY,
    team_code VARCHAR(64) NOT NULL,
    team_name VARCHAR(120) NOT NULL,
    UNIQUE KEY uk_service_team_code (team_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE team_member (
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (team_id, user_id),
    KEY idx_team_member_user (user_id),
    CONSTRAINT fk_team_member_team FOREIGN KEY (team_id) REFERENCES service_team(id),
    CONSTRAINT fk_team_member_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE staff_skill (
    user_id BIGINT NOT NULL,
    skill_code VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, skill_code),
    CONSTRAINT fk_staff_skill_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE service_contract (
    id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    contract_code VARCHAR(80) NOT NULL,
    service_tier VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    valid_from DATETIME(3) NOT NULL,
    valid_until DATETIME(3) NOT NULL COMMENT 'exclusive end of validity interval',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_service_contract_code (contract_code),
    KEY idx_service_contract_customer_status (customer_id, status),
    CONSTRAINT chk_contract_time CHECK (valid_until > valid_from),
    CONSTRAINT fk_contract_customer FOREIGN KEY (customer_id) REFERENCES customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contract_entitlement (
    id BIGINT PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    service_type VARCHAR(64) NOT NULL,
    is_enabled TINYINT NOT NULL DEFAULT 1,
    UNIQUE KEY uk_contract_entitlement (contract_id, service_type),
    CONSTRAINT fk_entitlement_contract FOREIGN KEY (contract_id) REFERENCES service_contract(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE service_calendar_version (
    id BIGINT PRIMARY KEY,
    calendar_code VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    timezone_name VARCHAR(64) NOT NULL COMMENT 'IANA zone, e.g. Asia/Shanghai',
    weekly_schedule_json JSON NOT NULL,
    date_overrides_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_calendar_version (calendar_code, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sla_policy_version (
    id BIGINT PRIMARY KEY,
    policy_code VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    calendar_version_id BIGINT NOT NULL,
    service_tier VARCHAR(40) NOT NULL,
    service_type VARCHAR(64) NOT NULL,
    priority VARCHAR(24) NOT NULL,
    response_target_minutes INT NOT NULL,
    resolution_target_minutes INT NOT NULL,
    pause_on_customer_wait TINYINT NOT NULL DEFAULT 1,
    pause_on_acceptance_wait TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_sla_policy_version (policy_code, version_no),
    KEY idx_sla_policy_match (service_tier, service_type, priority),
    CONSTRAINT chk_sla_targets CHECK (response_target_minutes > 0 AND resolution_target_minutes > 0),
    CONSTRAINT fk_sla_calendar FOREIGN KEY (calendar_version_id) REFERENCES service_calendar_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE service_ticket (
    id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    contract_id BIGINT NULL COMMENT 'P0 requires an existing contract; nullable only for future extension',
    creator_user_id BIGINT NOT NULL,
    title VARCHAR(240) NOT NULL,
    description TEXT NOT NULL,
    service_type VARCHAR(64) NOT NULL,
    category VARCHAR(64) NULL,
    impact_level VARCHAR(24) NOT NULL,
    urgency_level VARCHAR(24) NOT NULL,
    priority VARCHAR(24) NOT NULL COMMENT 'deterministically assigned at creation',
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    reopen_count INT NOT NULL DEFAULT 0,
    current_resolution_cycle_no INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    closed_at DATETIME(3) NULL,
    KEY idx_ticket_customer_created (customer_id, created_at),
    KEY idx_ticket_status_updated (status, updated_at),
    CONSTRAINT fk_ticket_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_ticket_contract FOREIGN KEY (contract_id) REFERENCES service_contract(id),
    CONSTRAINT fk_ticket_creator FOREIGN KEY (creator_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_entitlement_review (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(500) NOT NULL COMMENT 'request reason',
    decision_reason VARCHAR(500) NULL COMMENT 'must be present when approved or denied',
    reviewer_user_id BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_entitlement_review_ticket (ticket_id),
    CONSTRAINT fk_entitlement_review_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_entitlement_review_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE service_task (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    title VARCHAR(240) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    is_blocking TINYINT NOT NULL DEFAULT 1,
    waiting_on_customer TINYINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    current_assignee_id BIGINT NULL COMMENT 'single current assignee; immutable history stored separately',
    version INT NOT NULL DEFAULT 0,
    creator_user_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    KEY idx_task_ticket_status (ticket_id, status),
    KEY idx_task_assignee_status (current_assignee_id, status),
    CONSTRAINT fk_task_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_task_assignee FOREIGN KEY (current_assignee_id) REFERENCES app_user(id),
    CONSTRAINT fk_task_creator FOREIGN KEY (creator_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_assignment_history (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    previous_assignee_id BIGINT NULL,
    new_assignee_id BIGINT NOT NULL,
    assigned_by BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    assigned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_assignment_task_time (task_id, assigned_at),
    CONSTRAINT fk_assignment_task FOREIGN KEY (task_id) REFERENCES service_task(id),
    CONSTRAINT fk_assignment_prev FOREIGN KEY (previous_assignee_id) REFERENCES app_user(id),
    CONSTRAINT fk_assignment_new FOREIGN KEY (new_assignee_id) REFERENCES app_user(id),
    CONSTRAINT fk_assignment_by FOREIGN KEY (assigned_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_activity (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    task_id BIGINT NULL,
    actor_user_id BIGINT NOT NULL,
    activity_type VARCHAR(40) NOT NULL,
    is_customer_visible TINYINT NOT NULL DEFAULT 0,
    body TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
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
    submitted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_resolution_cycle (ticket_id, cycle_no),
    CONSTRAINT fk_resolution_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_resolution_submitter FOREIGN KEY (submitted_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_acceptance (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    resolution_id BIGINT NOT NULL,
    cycle_no INT NOT NULL,
    decision VARCHAR(16) NOT NULL COMMENT 'ACCEPT / REJECT',
    decided_by BIGINT NOT NULL,
    reason VARCHAR(1000) NULL,
    decided_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_acceptance_cycle (ticket_id, cycle_no),
    UNIQUE KEY uk_acceptance_resolution (resolution_id),
    CONSTRAINT fk_acceptance_resolution FOREIGN KEY (resolution_id) REFERENCES ticket_resolution(id),
    CONSTRAINT fk_acceptance_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_acceptance_user FOREIGN KEY (decided_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_sla_instance (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    policy_version_id BIGINT NOT NULL,
    calendar_version_id BIGINT NOT NULL,
    effective_start_at DATETIME(3) NOT NULL,
    response_due_at DATETIME(3) NOT NULL,
    resolution_due_at DATETIME(3) NOT NULL,
    response_recorded_at DATETIME(3) NULL,
    resolution_clock_state VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    resolution_elapsed_seconds BIGINT NOT NULL DEFAULT 0,
    resolution_last_started_at DATETIME(3) NULL,
    response_met TINYINT NULL,
    resolution_met TINYINT NULL,
    version INT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_sla_instance_ticket (ticket_id),
    CONSTRAINT chk_sla_elapsed CHECK (resolution_elapsed_seconds >= 0),
    CONSTRAINT fk_sla_instance_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_sla_instance_policy FOREIGN KEY (policy_version_id) REFERENCES sla_policy_version(id),
    CONSTRAINT fk_sla_instance_calendar FOREIGN KEY (calendar_version_id) REFERENCES service_calendar_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_sla_event (
    id BIGINT PRIMARY KEY,
    sla_instance_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    event_at DATETIME(3) NOT NULL,
    reason VARCHAR(600) NULL,
    actor_user_id BIGINT NULL,
    UNIQUE KEY uk_sla_sequence (sla_instance_id, sequence_no),
    KEY idx_sla_event_time (sla_instance_id, event_at),
    CONSTRAINT fk_sla_event_instance FOREIGN KEY (sla_instance_id) REFERENCES ticket_sla_instance(id),
    CONSTRAINT fk_sla_event_actor FOREIGN KEY (actor_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_action_proposal (
    id BIGINT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    initiated_by BIGINT NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    proposed_assignee_id BIGINT NOT NULL,
    expected_ticket_version INT NOT NULL,
    expected_task_version INT NOT NULL,
    proposal_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
    explanation_text VARCHAR(1500) NOT NULL,
    evidence_refs_json JSON NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    prompt_template_version VARCHAR(40) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    decided_by BIGINT NULL,
    decided_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_proposal_reviewer_queue (proposal_status, expires_at),
    KEY idx_proposal_task (task_id, created_at),
    CONSTRAINT fk_proposal_ticket FOREIGN KEY (ticket_id) REFERENCES service_ticket(id),
    CONSTRAINT fk_proposal_task FOREIGN KEY (task_id) REFERENCES service_task(id),
    CONSTRAINT fk_proposal_initiator FOREIGN KEY (initiated_by) REFERENCES app_user(id),
    CONSTRAINT fk_proposal_assignee FOREIGN KEY (proposed_assignee_id) REFERENCES app_user(id),
    CONSTRAINT fk_proposal_decider FOREIGN KEY (decided_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE action_execution (
    id BIGINT PRIMARY KEY,
    proposal_id BIGINT NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    execution_status VARCHAR(24) NOT NULL,
    execution_key VARCHAR(128) NOT NULL,
    effective_actor_id BIGINT NOT NULL,
    affected_task_id BIGINT NOT NULL,
    failure_code VARCHAR(64) NULL,
    executed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_execution_proposal (proposal_id),
    UNIQUE KEY uk_execution_key (execution_key),
    CONSTRAINT fk_execution_proposal FOREIGN KEY (proposal_id) REFERENCES ai_action_proposal(id),
    CONSTRAINT fk_execution_actor FOREIGN KEY (effective_actor_id) REFERENCES app_user(id),
    CONSTRAINT fk_execution_task FOREIGN KEY (affected_task_id) REFERENCES service_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE business_audit_log (
    id BIGINT PRIMARY KEY,
    actor_user_id BIGINT NULL,
    actor_kind VARCHAR(24) NOT NULL COMMENT 'HUMAN / AI_ASSISTED / SYSTEM',
    customer_id BIGINT NULL,
    ticket_id BIGINT NULL,
    action_code VARCHAR(80) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id BIGINT NOT NULL,
    request_id VARCHAR(80) NULL,
    detail_json JSON NOT NULL,
    occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_audit_ticket_time (ticket_id, occurred_at),
    KEY idx_audit_resource (resource_type, resource_id),
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
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uk_idempotency_actor_action (actor_user_id, action_code, idempotency_key),
    CONSTRAINT fk_idempotency_actor FOREIGN KEY (actor_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- P0 design rules NOT expressible with basic foreign keys:
-- 1. service_ticket.contract_id must belong to service_ticket.customer_id.
-- 2. ai_action_proposal.task_id must belong to ai_action_proposal.ticket_id.
-- 3. reviewer must have permission for resource at approval time (not creation time only).
-- 4. service_task.current_assignee_id is the sole source of current assignment;
--    task_assignment_history is append-only historical evidence, not another current flag.
-- 5. Policy/calendar versions must be IMMUTABLE once referenced by an SLA instance.
-- 6. The actual AI permission checks, service-state validations and idem behavior live
--    in Java transactional application services and are verified with integration tests.
-- 7. ticket_acceptance.ticket_id and cycle_no must match its ticket_resolution.
-- 8. In P1, event publication storage can be supplied by Spring Modulith 1.4.x's JDBC
--    starter (if using Spring Boot 3.5.x); do not also create a competing event outbox
--    without an explicit architecture decision.
