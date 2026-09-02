-- EC2 백엔드 서버 프로비저닝(C2). RDS(provisioned_databases)와 형제 테이블이다. 과금 자원이라
-- 승인을 거치고(approval_id), 생성이 비동기라 상태를 단계적으로 넘긴다. cloud_connection_id 는
-- 생성에 쓴 그 연결 — 상태 워커가 프로젝트의 '현재' 선택이 아니라 이 값으로 조회해야, 도중에 연결이
-- 바뀌어도 살아있는 인스턴스를 오판(고아 과금)하지 않는다(provisioned_databases 와 같은 이유).
CREATE TABLE provisioned_servers (
    server_id           BIGINT       NOT NULL AUTO_INCREMENT,
    project_id          BIGINT       NOT NULL,
    instance_type       VARCHAR(32)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    cloud_connection_id BIGINT       NULL,
    instance_id         VARCHAR(40)  NULL,
    public_host         VARCHAR(255) NULL,
    port                INT          NOT NULL,
    approval_id         BIGINT       NULL,
    failure_code        VARCHAR(40)  NULL,
    error_message       TEXT         NULL,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    PRIMARY KEY (server_id),
    KEY idx_provisioned_servers_project (project_id),
    KEY idx_provisioned_servers_status (status),
    KEY idx_provisioned_servers_approval (approval_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
