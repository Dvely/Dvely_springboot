SET NAMES utf8mb4;

CREATE TABLE deployment_failure_analyses (
    analysis_id BIGINT NOT NULL AUTO_INCREMENT,
    history_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    source VARCHAR(20) NOT NULL COMMENT 'LLM | RULE_BASED',
    summary TEXT NOT NULL,
    log_excerpt MEDIUMTEXT NOT NULL,
    suggested_fix TEXT NOT NULL,
    provider VARCHAR(20) NULL COMMENT '분석에 사용한 AI provider (RULE_BASED면 NULL)',
    model VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (analysis_id),
    UNIQUE KEY uk_deployment_failure_analyses_history (history_id),
    CONSTRAINT fk_deployment_failure_analyses_history
        FOREIGN KEY (history_id) REFERENCES deployment_histories (history_id) ON DELETE CASCADE,
    CONSTRAINT fk_deployment_failure_analyses_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='배포 실패 원인 분석 결과 (이력당 최대 1건)';

ALTER TABLE deployment_histories
    ADD COLUMN retried_from_history_id BIGINT NULL AFTER lease_until,
    ADD KEY idx_deployment_histories_retried_from (retried_from_history_id),
    ADD CONSTRAINT fk_deployment_histories_retried_from
        FOREIGN KEY (retried_from_history_id)
        REFERENCES deployment_histories (history_id) ON DELETE SET NULL;
