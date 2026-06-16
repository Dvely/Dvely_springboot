SET NAMES utf8mb4;

UPDATE cloud_connections
SET status = 'VALIDATED'
WHERE status = 'CHECKING';

ALTER TABLE cloud_connections
    MODIFY COLUMN status VARCHAR(40) NOT NULL DEFAULT 'VALIDATED'
        COMMENT 'VALIDATED | VERIFYING | CONNECTED | provider error status';

CREATE TABLE cloud_connection_verification_jobs (
    job_id VARCHAR(36) NOT NULL,
    cloud_connection_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    connection_status VARCHAR(40) NOT NULL DEFAULT 'VALIDATED',
    message TEXT NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(120) NULL,
    lease_until DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME NULL,
    completed_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (job_id),
    KEY idx_cloud_verification_jobs_connection (cloud_connection_id, created_at),
    KEY idx_cloud_verification_jobs_queue (status, created_at),
    KEY idx_cloud_verification_jobs_lease (status, lease_until),
    KEY idx_cloud_verification_jobs_user (user_id),
    CONSTRAINT fk_cloud_verification_jobs_connection
        FOREIGN KEY (cloud_connection_id)
        REFERENCES cloud_connections (cloud_connection_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_cloud_verification_jobs_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='AWS/GCP 실제 권한 확인 Job';

CREATE TABLE project_cloud_connection_settings (
    project_id BIGINT NOT NULL,
    cloud_connection_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id),
    KEY idx_project_cloud_settings_connection (cloud_connection_id),
    CONSTRAINT fk_project_cloud_settings_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_project_cloud_settings_connection
        FOREIGN KEY (cloud_connection_id)
        REFERENCES cloud_connections (cloud_connection_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='프로젝트별 선택된 BYOC 클라우드 연결';
