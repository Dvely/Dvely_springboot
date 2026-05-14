-- SNAPSHOT ONLY
-- 실제 스키마 변경은 src/main/resources/db/migration 의 Flyway 파일로 관리한다.

SET NAMES utf8mb4;

CREATE TABLE users (
    user_id BIGINT NOT NULL AUTO_INCREMENT,
    github_user_id VARCHAR(255) NOT NULL COMMENT 'GitHub 고유 ID',
    user_name VARCHAR(255) NOT NULL COMMENT 'GitHub username',
    avatar_url VARCHAR(512) NULL COMMENT 'GitHub 프로필 이미지 URL',
    access_token TEXT NULL COMMENT 'GitHub API 호출용 Access Token(암호화 저장 권장)',
    scope VARCHAR(255) NULL COMMENT '토큰 권한 범위 (예: repo, workflow 등)',
    token_expires_at DATETIME NULL COMMENT 'Access Token 만료 시간 (없을 수도 있음)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '계정 생성 일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '계정 수정 일시',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_users_github_user_id (github_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE repositories (
    repository_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    repo_id VARCHAR(255) NOT NULL COMMENT 'GitHub 레포지토리 고유 ID',
    repo_name VARCHAR(255) NOT NULL COMMENT '레포지토리 이름',
    is_private TINYINT(1) NOT NULL COMMENT '레포지토리 공개 여부 (0: public, 1: private)',
    default_branch VARCHAR(100) NOT NULL DEFAULT 'main' COMMENT '기본 브랜치 (예: main)',
    repo_url VARCHAR(512) NOT NULL COMMENT 'GitHub 레포지토리 URL',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'repo 사용 등록 일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'repo 수정 일시',
    PRIMARY KEY (repository_id),
    UNIQUE KEY uk_repositories_repo_id (repo_id),
    KEY idx_repositories_user_id (user_id),
    CONSTRAINT fk_repositories_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE pipelines (
    pipeline_id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (pipeline_id),
    KEY idx_pipelines_repository_id (repository_id),
    CONSTRAINT fk_pipelines_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (repository_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE deployments (
    deployment_id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    pipeline_id BIGINT NOT NULL,
    deploy_url VARCHAR(512) NOT NULL COMMENT '배포된 서비스 URL',
    status VARCHAR(50) NOT NULL COMMENT '배포 상태 (pending, success, failed)',
    log TEXT NOT NULL COMMENT '배포 로그',
    deployed_at DATETIME NOT NULL COMMENT '배포 시작 시간',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (deployment_id),
    KEY idx_deployments_repository_id (repository_id),
    KEY idx_deployments_pipeline_id (pipeline_id),
    CONSTRAINT fk_deployments_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (repository_id),
    CONSTRAINT fk_deployments_pipeline
        FOREIGN KEY (pipeline_id)
        REFERENCES pipelines (pipeline_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE domains (
    domain_id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    domain_name VARCHAR(255) NOT NULL COMMENT '도메인 주소',
    domain_type VARCHAR(30) NOT NULL DEFAULT 'CUSTOM_DOMAIN',
    status VARCHAR(30) NOT NULL DEFAULT 'VERIFYING',
    verification_method VARCHAR(10) NULL,
    dns_target VARCHAR(512) NULL,
    cloudflare_record_id VARCHAR(100) NULL,
    last_checked_at DATETIME NULL,
    https_enforced TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'HTTPS 강제 여부 (0: off, 1: on)',
    dns_verified TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'DNS 인증 여부 (0: 미인증, 1: 인증완료)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '도메인 등록 시간',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '도메인 수정 시간',
    PRIMARY KEY (domain_id),
    UNIQUE KEY uk_domains_domain_name (domain_name),
    KEY idx_domains_repository_id (repository_id),
    CONSTRAINT fk_domains_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (repository_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chat_sessions (
    chat_session_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '사용자의 레포에 대한 채팅 세션 id',
    user_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '삭제 여부 (0: 사용, 1: 삭제)',
    deleted_at DATETIME NULL COMMENT '채팅 휴지통 처리 시점',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '채팅 세션 생성 시간',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '채팅 세션 수정 시간',
    PRIMARY KEY (chat_session_id),
    KEY idx_chat_sessions_user_id (user_id),
    KEY idx_chat_sessions_repository_id (repository_id),
    CONSTRAINT fk_chat_sessions_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id),
    CONSTRAINT fk_chat_sessions_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (repository_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chat_messages (
    message_id BIGINT NOT NULL AUTO_INCREMENT,
    chat_session_id BIGINT NOT NULL COMMENT '사용자의 레포에 대한 채팅 세션 id',
    role ENUM('user', 'assistant', 'system') NOT NULL COMMENT '작성자 역할 (user, assistant, system)',
    content TEXT NOT NULL COMMENT '채팅 내용',
    token_count BIGINT NOT NULL DEFAULT 0 COMMENT 'LLM 사용 토큰 수',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '메시지 생성 시간',
    PRIMARY KEY (message_id),
    KEY idx_chat_messages_chat_session_id (chat_session_id),
    CONSTRAINT fk_chat_messages_session
        FOREIGN KEY (chat_session_id)
        REFERENCES chat_sessions (chat_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
