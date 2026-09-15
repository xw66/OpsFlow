ALTER TABLE ticket
    ADD create_request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD create_request_hash CHAR(64) NULL,
    ADD create_response JSON NULL,
    ADD UNIQUE KEY uk_ticket_create_request (user_id, create_request_key);
