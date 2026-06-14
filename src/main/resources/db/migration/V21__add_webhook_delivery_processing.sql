SET NAMES utf8mb4;

ALTER TABLE projects
    ADD COLUMN repository_head_sha VARCHAR(40) NULL AFTER repository_health,
    ADD COLUMN repository_head_message VARCHAR(1000) NULL AFTER repository_head_sha,
    ADD COLUMN repository_head_author VARCHAR(255) NULL AFTER repository_head_message,
    ADD COLUMN repository_head_committed_at DATETIME NULL AFTER repository_head_author,
    ADD COLUMN repository_head_synced_at DATETIME NULL AFTER repository_head_committed_at,
    ADD COLUMN repository_version VARCHAR(100) NULL AFTER repository_head_synced_at,
    ADD COLUMN repository_version_synced_at DATETIME NULL AFTER repository_version;

CREATE TABLE webhook_deliveries (
    delivery_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload LONGBLOB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(120) NULL,
    lease_until DATETIME NULL,
    error_message TEXT NULL,
    received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (delivery_id),
    KEY idx_webhook_deliveries_queue (status, next_attempt_at),
    KEY idx_webhook_deliveries_lease (status, lease_until),
    KEY idx_webhook_deliveries_event (event_type, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='GitHub webhook delivery idempotency and retry queue';
