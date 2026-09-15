ALTER TABLE ticket
    ADD response_breached BOOLEAN NOT NULL DEFAULT FALSE,
    ADD resolve_breached BOOLEAN NOT NULL DEFAULT FALSE,
    ADD escalation_level INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_ticket_escalation CHECK (escalation_level BETWEEN 0 AND 1);

CREATE TABLE sla_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    sla_cycle INT NOT NULL,
    type VARCHAR(32) NOT NULL,
    deadline DATETIME(6) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    UNIQUE KEY uk_sla_cycle_type (ticket_id, sla_cycle, type),
    UNIQUE KEY uk_sla_event_id (event_id),
    FOREIGN KEY (ticket_id) REFERENCES ticket(id),
    CONSTRAINT chk_sla_cycle CHECK (sla_cycle > 0),
    CONSTRAINT chk_sla_type CHECK (type IN ('RESPONSE_WARNING','RESPONSE_BREACHED','RESOLVE_WARNING','RESOLVE_BREACHED'))
) ENGINE=InnoDB;
