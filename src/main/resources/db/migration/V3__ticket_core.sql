CREATE TABLE ticket (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    assignee_id BIGINT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    version BIGINT NOT NULL DEFAULT 0,
    sla_policy_id BIGINT NOT NULL,
    response_minutes INT NOT NULL,
    resolve_minutes INT NOT NULL,
    auto_escalate BOOLEAN NOT NULL,
    response_deadline DATETIME(6) NOT NULL,
    resolve_deadline DATETIME(6) NOT NULL,
    first_response_at DATETIME(6) NULL,
    resolved_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_ticket_user_created (user_id, created_at),
    KEY idx_ticket_assignee_status (assignee_id, status),
    KEY idx_ticket_status_response (status, response_deadline),
    KEY idx_ticket_status_resolve (status, resolve_deadline),
    KEY idx_ticket_category_priority (category_id, priority),
    KEY idx_ticket_group_status (group_id, status),
    FOREIGN KEY (user_id) REFERENCES app_user(id),
    FOREIGN KEY (category_id) REFERENCES ticket_category(id),
    FOREIGN KEY (group_id) REFERENCES support_group(id),
    FOREIGN KEY (assignee_id) REFERENCES app_user(id),
    FOREIGN KEY (sla_policy_id) REFERENCES sla_policy(id),
    CHECK (status IN ('CREATED','ASSIGNED','PROCESSING','PENDING','RESOLVED','CLOSED','CANCELLED')),
    CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT'))
) ENGINE=InnoDB;

CREATE TABLE ticket_status_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    from_status VARCHAR(16) NULL,
    to_status VARCHAR(16) NOT NULL,
    operator_id BIGINT NULL,
    remark VARCHAR(500) NOT NULL,
    ticket_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_history_version (ticket_id, ticket_version),
    KEY idx_history_ticket_created (ticket_id, created_at),
    FOREIGN KEY (ticket_id) REFERENCES ticket(id),
    FOREIGN KEY (operator_id) REFERENCES app_user(id)
) ENGINE=InnoDB;

CREATE TABLE ticket_comment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    internal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    KEY idx_comment_ticket_created (ticket_id, created_at),
    FOREIGN KEY (ticket_id) REFERENCES ticket(id),
    FOREIGN KEY (author_id) REFERENCES app_user(id)
) ENGINE=InnoDB;

CREATE TABLE ticket_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(32) NOT NULL,
    UNIQUE KEY uk_tag_name (name)
) ENGINE=InnoDB;

CREATE TABLE ticket_tag_relation (
    ticket_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (ticket_id, tag_id),
    FOREIGN KEY (ticket_id) REFERENCES ticket(id),
    FOREIGN KEY (tag_id) REFERENCES ticket_tag(id)
) ENGINE=InnoDB;

CREATE TABLE ticket_attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    uploader_id BIGINT NOT NULL,
    original_name VARCHAR(128) NOT NULL,
    storage_key VARCHAR(36) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_attachment_key (storage_key),
    KEY idx_attachment_ticket (ticket_id),
    FOREIGN KEY (ticket_id) REFERENCES ticket(id),
    FOREIGN KEY (uploader_id) REFERENCES app_user(id)
) ENGINE=InnoDB;

CREATE TABLE outbox_event (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    aggregate_id BIGINT NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NULL,
    KEY idx_outbox_status_created (status, created_at)
) ENGINE=InnoDB;
