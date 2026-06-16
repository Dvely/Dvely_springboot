SET NAMES utf8mb4;

CREATE TABLE project_changes (
    change_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    chat_session_id BIGINT NULL,
    task_id VARCHAR(64) NOT NULL,
    preview_session_id VARCHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    summary TEXT NULL,
    diff_text MEDIUMTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (change_id),
    UNIQUE KEY uk_project_changes_task_id (task_id),
    KEY idx_project_changes_project_created (project_id, created_at),
    CONSTRAINT fk_project_changes_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id),
    CONSTRAINT fk_project_changes_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id),
    CONSTRAINT fk_project_changes_chat_session
        FOREIGN KEY (chat_session_id)
        REFERENCES chat_sessions (chat_session_id),
    CONSTRAINT fk_project_changes_task
        FOREIGN KEY (task_id)
        REFERENCES agent_runs (task_id),
    CONSTRAINT fk_project_changes_preview_session
        FOREIGN KEY (preview_session_id)
        REFERENCES preview_sessions (preview_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
