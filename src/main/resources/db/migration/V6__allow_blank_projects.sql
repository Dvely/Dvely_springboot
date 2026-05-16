SET NAMES utf8mb4;

ALTER TABLE repositories
    MODIFY COLUMN repo_id VARCHAR(255) NULL COMMENT 'GitHub 레포지토리 고유 ID',
    MODIFY COLUMN repo_name VARCHAR(255) NULL COMMENT '레포지토리 이름',
    MODIFY COLUMN is_private TINYINT(1) NULL COMMENT '레포지토리 공개 여부 (0: public, 1: private)',
    MODIFY COLUMN default_branch VARCHAR(100) NULL DEFAULT NULL COMMENT '기본 브랜치 (예: main)',
    MODIFY COLUMN repo_url VARCHAR(512) NULL COMMENT 'GitHub 레포지토리 URL';
