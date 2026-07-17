SET NAMES utf8mb4;

ALTER TABLE projects
    ADD COLUMN repository_connected_at DATETIME NULL
        COMMENT 'GitHub 저장소 연결 시각. 해제 시 NULL, 레거시 연결 행은 NULL(백필 안 함)'
        AFTER repository_version_synced_at;
