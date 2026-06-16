SET NAMES utf8mb4;

ALTER TABLE deployment_histories
    ADD COLUMN user_id BIGINT NULL AFTER history_id,
    ADD COLUMN correlation_id VARCHAR(36) NULL AFTER workflow_run_id,
    ADD COLUMN commit_sha VARCHAR(40) NULL AFTER correlation_id,
    ADD COLUMN workflow_head_sha VARCHAR(40) NULL AFTER commit_sha,
    ADD COLUMN title VARCHAR(500) NULL AFTER workflow_head_sha,
    ADD COLUMN description TEXT NULL AFTER title,
    ADD COLUMN merged_by VARCHAR(100) NULL AFTER description,
    ADD COLUMN merged_by_avatar_url VARCHAR(1000) NULL AFTER merged_by,
    ADD COLUMN pr_number INT NULL AFTER merged_by_avatar_url,
    ADD COLUMN merged_at DATETIME NULL AFTER pr_number,
    ADD COLUMN agent_task_id VARCHAR(64) NULL AFTER merged_at,
    ADD COLUMN error_message TEXT NULL AFTER agent_task_id,
    ADD COLUMN attempt INT NOT NULL DEFAULT 0 AFTER error_message,
    ADD COLUMN max_attempts INT NOT NULL DEFAULT 3 AFTER attempt,
    ADD COLUMN next_run_at DATETIME NULL AFTER max_attempts,
    ADD COLUMN lease_owner VARCHAR(100) NULL AFTER next_run_at,
    ADD COLUMN lease_until DATETIME NULL AFTER lease_owner,
    MODIFY COLUMN deployed_url VARCHAR(512) NULL;

UPDATE deployment_histories history
JOIN projects project ON project.project_id = history.project_id
SET history.user_id = project.user_id,
    history.correlation_id = UUID()
WHERE history.user_id IS NULL
   OR history.correlation_id IS NULL;

ALTER TABLE deployment_histories
    MODIFY COLUMN user_id BIGINT NOT NULL,
    MODIFY COLUMN correlation_id VARCHAR(36) NOT NULL,
    ADD UNIQUE KEY uk_deployment_histories_correlation_id (correlation_id),
    ADD UNIQUE KEY uk_deployment_histories_workflow_run_id (workflow_run_id),
    ADD KEY idx_deployment_histories_queue (status, next_run_at),
    ADD KEY idx_deployment_histories_lease (status, lease_until),
    ADD KEY idx_deployment_histories_commit_sha (project_id, commit_sha),
    ADD CONSTRAINT fk_deployment_histories_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id);
