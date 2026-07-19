SET NAMES utf8mb4;

-- BI-097: agent task 없이 생성되는 standalone 승인 허용 (D6)
ALTER TABLE approvals
    MODIFY COLUMN task_id VARCHAR(64) NULL
        COMMENT 'Agent task 승인은 값 있음, standalone(직접 요청) 승인은 NULL';

CREATE TABLE project_infrastructure_settings (
    project_id BIGINT NOT NULL,
    deployment_architecture VARCHAR(20) NOT NULL COMMENT 'SERVER | CONTAINER | SERVERLESS',
    compute_tier VARCHAR(20) NOT NULL COMMENT 'MICRO | SMALL | MEDIUM | LARGE (provider-중립 티어)',
    storage_type VARCHAR(30) NOT NULL COMMENT 'NONE | OBJECT_STORAGE',
    network_access VARCHAR(20) NOT NULL COMMENT 'PUBLIC | PRIVATE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id),
    CONSTRAINT fk_project_infra_settings_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='프로젝트별 적용된 인프라 설정 (desired state, 실제 클라우드 반영은 EPIC 15)';

CREATE TABLE project_infrastructure_setting_changes (
    change_id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL COMMENT 'CREATED | UPDATED',
    status VARCHAR(30) NOT NULL COMMENT 'APPLIED | PENDING_APPROVAL | REJECTED',
    deployment_architecture VARCHAR(20) NOT NULL,
    compute_tier VARCHAR(20) NOT NULL,
    storage_type VARCHAR(30) NOT NULL,
    network_access VARCHAR(20) NOT NULL,
    approval_id BIGINT NULL COMMENT '즉시 적용 변경은 NULL',
    actor_user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at DATETIME NULL COMMENT 'APPLIED/REJECTED 확정 시각',
    PRIMARY KEY (change_id),
    KEY idx_infra_setting_changes_project (project_id, created_at),
    KEY idx_infra_setting_changes_pending (project_id, status),
    KEY idx_infra_setting_changes_approval (approval_id),
    CONSTRAINT fk_infra_setting_changes_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_infra_setting_changes_approval
        FOREIGN KEY (approval_id)
        REFERENCES approvals (approval_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_infra_setting_changes_actor
        FOREIGN KEY (actor_user_id)
        REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='인프라 설정 변경 이력 + 승인 대기 변경 (값 스냅샷 저장, append-only + status 전이만)';
