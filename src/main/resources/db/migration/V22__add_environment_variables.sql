SET NAMES utf8mb4;

CREATE TABLE environment_variables (
    environment_variable_id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    scope VARCHAR(20) NOT NULL COMMENT 'PREVIEW | PRODUCTION',
    env_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'POSIX 환경변수 키 (case-sensitive)',
    env_value MEDIUMTEXT NOT NULL COMMENT 'AES-256-GCM 암호문(Base64), 애플리케이션 레벨 암호화',
    secret TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (environment_variable_id),
    UNIQUE KEY uk_environment_variables_project_scope_key (project_id, scope, env_key),
    CONSTRAINT fk_environment_variables_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='프로젝트별 환경변수 (값은 secret 여부와 무관하게 전체 암호화 저장)';

CREATE TABLE environment_variable_histories (
    environment_variable_history_id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    environment_variable_id BIGINT NULL COMMENT '원본 삭제 시 NULL',
    scope VARCHAR(20) NOT NULL,
    env_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    action VARCHAR(20) NOT NULL COMMENT 'CREATED | UPDATED | DELETED',
    secret TINYINT(1) NOT NULL,
    value_changed TINYINT(1) NOT NULL DEFAULT 0,
    actor_user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (environment_variable_history_id),
    KEY idx_env_var_histories_project (project_id, created_at),
    KEY idx_env_var_histories_variable (environment_variable_id),
    CONSTRAINT fk_env_var_histories_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_env_var_histories_variable
        FOREIGN KEY (environment_variable_id)
        REFERENCES environment_variables (environment_variable_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_env_var_histories_actor
        FOREIGN KEY (actor_user_id)
        REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='환경변수 변경 이력 (값 자체는 저장하지 않음, append-only)';
