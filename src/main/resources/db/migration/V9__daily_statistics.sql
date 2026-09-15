ALTER TABLE ticket ADD KEY idx_ticket_created_group (created_at,group_id);

CREATE TABLE statistics_day (
    business_date DATE PRIMARY KEY,
    requested BOOLEAN NOT NULL DEFAULT TRUE,
    refreshed_at DATETIME(6) NULL,
    KEY idx_statistics_requested (requested,business_date)
) ENGINE=InnoDB;

CREATE TABLE statistics_daily (
    business_date DATE NOT NULL,
    group_id BIGINT NOT NULL,
    created_count BIGINT NOT NULL,
    PRIMARY KEY (business_date,group_id),
    FOREIGN KEY (group_id) REFERENCES support_group(id)
) ENGINE=InnoDB;
