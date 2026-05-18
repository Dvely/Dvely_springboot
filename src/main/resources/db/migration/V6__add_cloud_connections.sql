SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS cloud_connections (
    cloud_connection_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL COMMENT 'AWS | GCP',
    display_name VARCHAR(100) NOT NULL COMMENT '사용자가 구분하기 위한 연결 이름',
    account_id VARCHAR(50) NULL COMMENT 'AWS account ID',
    region VARCHAR(80) NOT NULL COMMENT '배포 기본 리전',
    role_arn VARCHAR(255) NULL COMMENT 'AWS AssumeRole ARN',
    gcp_project_id VARCHAR(255) NULL COMMENT 'GCP project ID',
    service_account_email VARCHAR(255) NULL COMMENT 'GCP service account email',
    status VARCHAR(40) NOT NULL DEFAULT 'CHECKING' COMMENT '클라우드 연결 health 상태',
    last_checked_at DATETIME NULL COMMENT '마지막 health 확인 시각',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '클라우드 연결 등록 시각',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '클라우드 연결 수정 시각',
    PRIMARY KEY (cloud_connection_id),
    KEY idx_cloud_connections_user_id (user_id),
    KEY idx_cloud_connections_user_provider (user_id, provider),
    KEY idx_cloud_connections_user_status (user_id, status),
    CONSTRAINT fk_cloud_connections_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='외부 클라우드 BYOC 연결';
