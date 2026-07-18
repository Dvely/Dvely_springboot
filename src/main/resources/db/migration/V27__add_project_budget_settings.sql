SET NAMES utf8mb4;

CREATE TABLE project_budget_settings (
    project_id BIGINT NOT NULL,
    monthly_budget_amount DECIMAL(12,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id),
    CONSTRAINT fk_project_budget_settings_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='프로젝트별 월 예산 (비용 추정치는 저장하지 않음 — 온더플라이 계산)';
