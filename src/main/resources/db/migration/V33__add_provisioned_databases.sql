-- 백엔드 앱의 DB 프로비저닝 자원. 방식(LOCAL·RDS·DOCKER)·엔진·상태와 접속정보를 담는다.
--
-- password 는 AES 로 암호화 저장한다(cloud_connections 의 시크릿과 같은 방식). 조회 응답에는
-- 절대 싣지 않고 생성 응답에서만 한 번 노출한다.
--
-- expires_at 은 LOCAL 만 값을 갖는다. LOCAL DB 는 프리뷰 세션과 함께 사라지므로, 만료 회수
-- 워커가 이 값을 보고 status 를 EXPIRED 로 넘긴다 — 'READY 인데 실제로는 없는' 상태를 막는다.
CREATE TABLE provisioned_databases (
    database_id     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '프로비저닝 자원 식별자',
    project_id      BIGINT       NOT NULL COMMENT '소속 프로젝트',
    method          VARCHAR(20)  NOT NULL COMMENT 'LOCAL | RDS | DOCKER',
    engine          VARCHAR(20)  NOT NULL COMMENT 'POSTGRESQL | MYSQL',
    status          VARCHAR(20)  NOT NULL COMMENT 'PENDING | PROVISIONING | READY | FAILED | EXPIRED',
    resource_id     VARCHAR(255) NULL COMMENT '컨테이너/인스턴스 식별자 — 정리 대상 지목',
    host            VARCHAR(255) NULL COMMENT '접속 호스트',
    port            INT          NULL COMMENT '접속 포트',
    database_name   VARCHAR(64)  NULL COMMENT 'DB 이름',
    username        VARCHAR(64)  NULL COMMENT '접속 사용자',
    password        MEDIUMTEXT   NULL COMMENT 'AES encrypted DB password',
    expires_at      DATETIME     NULL COMMENT 'LOCAL 만 값. 이 시각 이후 회수 워커가 EXPIRED 로',
    failure_code    VARCHAR(40)  NULL COMMENT '실패 분류 (IAM_PERMISSION 등). 성공/진행 중이면 null',
    error_message   TEXT         NULL COMMENT '실패 상세',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (database_id),
    KEY idx_provisioned_databases_project (project_id),
    KEY idx_provisioned_databases_expiry (status, expires_at),
    CONSTRAINT fk_provisioned_databases_project FOREIGN KEY (project_id)
        REFERENCES projects (project_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
