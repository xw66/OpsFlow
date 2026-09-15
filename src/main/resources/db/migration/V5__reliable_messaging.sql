ALTER TABLE outbox_event
    ADD available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD lease_owner CHAR(36) NULL,
    ADD lease_until DATETIME(6) NULL,
    ADD KEY idx_outbox_available (status, available_at, event_id),
    ADD KEY idx_outbox_lease (status, lease_until);

CREATE TABLE consumed_event (
    consumer_name VARCHAR(64) NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
) ENGINE=InnoDB;

CREATE TABLE notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipient_id BIGINT NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_id BIGINT NOT NULL,
    content VARCHAR(500) NOT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_notification_recipient_event (recipient_id, event_id),
    KEY idx_notification_recipient_created (recipient_id, created_at),
    FOREIGN KEY (recipient_id) REFERENCES app_user(id),
    FOREIGN KEY (ticket_id) REFERENCES ticket(id)
) ENGINE=InnoDB;

CREATE TABLE consumer_failure (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    consumer_name VARCHAR(64) NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    topic VARCHAR(249) NOT NULL,
    partition_no INT NOT NULL,
    offset_no BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 1,
    error_type VARCHAR(200) NOT NULL,
    payload MEDIUMTEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    UNIQUE KEY uk_failure_record (consumer_name, topic, partition_no, offset_no),
    KEY idx_failure_event (consumer_name, event_id),
    KEY idx_failure_status (status, id)
) ENGINE=InnoDB;
