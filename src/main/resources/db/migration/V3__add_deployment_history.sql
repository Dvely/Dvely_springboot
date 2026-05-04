SET NAMES utf8mb4;

CREATE TABLE deployment_histories (
    history_id      BIGINT       NOT NULL AUTO_INCREMENT,
    project_id      BIGINT       NOT NULL COMMENT 'Dvely 프로젝트 ID',
    deploy_target_type VARCHAR(20) NOT NULL COMMENT 'LATEST | VERSION',
    version_label   VARCHAR(100) NULL     COMMENT 'VERSION 배포 시 태그명',
    deployed_url    VARCHAR(512) NOT NULL COMMENT 'GitHub Pages URL',
    status          VARCHAR(30)  NOT NULL COMMENT 'IN_PROGRESS | LIVE | FAILED',
    triggered_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '배포 트리거 시각',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '상태 변경 시각',
    PRIMARY KEY (history_id),
    KEY idx_deployment_histories_project_id (project_id),
    KEY idx_deployment_histories_project_status (project_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='배포 실행 이력';
