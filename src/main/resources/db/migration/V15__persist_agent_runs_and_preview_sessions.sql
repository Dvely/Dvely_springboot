SET NAMES utf8mb4;

CREATE TABLE agent_runs (
    task_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    chat_session_id BIGINT NULL,
    status VARCHAR(30) NOT NULL,
    plan_json LONGTEXT NULL,
    current_step INT NOT NULL DEFAULT 0,
    attempt INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    preview_url VARCHAR(1000) NULL,
    summary TEXT NULL,
    error TEXT NULL,
    question TEXT NULL,
    input_value TEXT NULL,
    failure_log TEXT NULL,
    suggested_fix TEXT NULL,
    next_run_at DATETIME NULL,
    lease_owner VARCHAR(100) NULL,
    lease_until DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id),
    KEY idx_agent_runs_user_created (user_id, created_at),
    KEY idx_agent_runs_queue (status, next_run_at),
    KEY idx_agent_runs_lease (status, lease_until),
    CONSTRAINT fk_agent_runs_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id),
    CONSTRAINT fk_agent_runs_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id),
    CONSTRAINT fk_agent_runs_chat_session
        FOREIGN KEY (chat_session_id)
        REFERENCES chat_sessions (chat_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_run_events (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    message TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id),
    KEY idx_agent_run_events_task_id (task_id, event_id),
    CONSTRAINT fk_agent_run_events_task
        FOREIGN KEY (task_id)
        REFERENCES agent_runs (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE preview_sessions (
    preview_session_id VARCHAR(36) NOT NULL,
    access_token VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    chat_session_id BIGINT NULL,
    task_id VARCHAR(64) NOT NULL,
    container_id VARCHAR(128) NOT NULL,
    host_port INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    public_url VARCHAR(1000) NOT NULL,
    expires_at DATETIME NOT NULL,
    last_accessed_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (preview_session_id),
    UNIQUE KEY uk_preview_sessions_access_token (access_token),
    KEY idx_preview_sessions_task_id (task_id),
    KEY idx_preview_sessions_owner_status (user_id, status),
    KEY idx_preview_sessions_expiry (status, expires_at),
    CONSTRAINT fk_preview_sessions_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id),
    CONSTRAINT fk_preview_sessions_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id),
    CONSTRAINT fk_preview_sessions_chat_session
        FOREIGN KEY (chat_session_id)
        REFERENCES chat_sessions (chat_session_id),
    CONSTRAINT fk_preview_sessions_task
        FOREIGN KEY (task_id)
        REFERENCES agent_runs (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
