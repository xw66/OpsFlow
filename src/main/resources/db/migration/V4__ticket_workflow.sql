ALTER TABLE ticket
    ADD COLUMN sla_cycle INT NOT NULL DEFAULT 1,
    ADD COLUMN cycle_started_at DATETIME(6) NULL;
UPDATE ticket SET cycle_started_at=created_at;
ALTER TABLE ticket MODIFY COLUMN cycle_started_at DATETIME(6) NOT NULL;

CREATE TABLE ticket_assignment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    from_assignee_id BIGINT NULL,
    to_assignee_id BIGINT NOT NULL,
    from_group_id BIGINT NOT NULL,
    to_group_id BIGINT NOT NULL,
    operator_id BIGINT NULL,
    reason VARCHAR(500) NOT NULL,
    ticket_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_assignment_version (ticket_id, ticket_version),
    KEY idx_assignment_ticket_created (ticket_id, created_at),
    FOREIGN KEY (ticket_id) REFERENCES ticket(id),
    FOREIGN KEY (from_assignee_id) REFERENCES app_user(id),
    FOREIGN KEY (to_assignee_id) REFERENCES app_user(id),
    FOREIGN KEY (from_group_id) REFERENCES support_group(id),
    FOREIGN KEY (to_group_id) REFERENCES support_group(id),
    FOREIGN KEY (operator_id) REFERENCES app_user(id)
) ENGINE=InnoDB;

CREATE INDEX idx_ticket_unassigned ON ticket(status, assignee_id, id);
