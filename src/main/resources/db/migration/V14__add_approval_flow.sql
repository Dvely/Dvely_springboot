SET NAMES utf8mb4;

CREATE TABLE project_approval_policies (
    project_id BIGINT NOT NULL,
    change_approval_required TINYINT(1) NOT NULL DEFAULT 1,
    deployment_approval_required TINYINT(1) NOT NULL DEFAULT 1,
    domain_approval_required TINYINT(1) NOT NULL DEFAULT 1,
    infra_approval_required TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (project_id),
    CONSTRAINT fk_project_approval_policies_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE approvals (
    approval_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    chat_session_id BIGINT NULL,
    task_id VARCHAR(64) NOT NULL,
    approval_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    summary VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at DATETIME NULL,
    PRIMARY KEY (approval_id),
    KEY idx_approvals_user_id (user_id),
    KEY idx_approvals_project_id (project_id),
    KEY idx_approvals_task_id (task_id),
    CONSTRAINT fk_approvals_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id),
    CONSTRAINT fk_approvals_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id),
    CONSTRAINT fk_approvals_chat_session
        FOREIGN KEY (chat_session_id)
        REFERENCES chat_sessions (chat_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
