CREATE TABLE support_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    leader_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_group_name (name),
    FOREIGN KEY (leader_id) REFERENCES app_user(id)
) ENGINE=InnoDB;

CREATE TABLE support_agent (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    online BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_assigned_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_agent_user (user_id),
    KEY idx_agent_group_enabled_online (group_id, enabled, online),
    FOREIGN KEY (user_id) REFERENCES app_user(id),
    FOREIGN KEY (group_id) REFERENCES support_group(id)
) ENGINE=InnoDB;

CREATE TABLE ticket_category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    group_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_category_code (code),
    FOREIGN KEY (group_id) REFERENCES support_group(id)
) ENGINE=InnoDB;

CREATE TABLE sla_policy (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    priority VARCHAR(16) NOT NULL,
    response_minutes INT NOT NULL,
    resolve_minutes INT NOT NULL,
    auto_escalate BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_sla_category_priority (category_id, priority),
    FOREIGN KEY (category_id) REFERENCES ticket_category(id),
    CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CHECK (response_minutes > 0 AND resolve_minutes >= response_minutes AND resolve_minutes <= 525600)
) ENGINE=InnoDB;
