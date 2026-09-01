-- 프리뷰가 프로젝트를 어떻게 실행·서빙하는지(런타임 타입·실행명령). 프로젝트당 한 행이다.
--
-- 미설정이면 서비스가 컨테이너의 클론 내용으로 자동 감지한 기본값을 쓴다(명시 설정이 있으면 우선).
-- api_path_prefix·health_path 는 JAVA_FULLSTACK(내부 nginx 라우팅)에서 쓰는 값이라 NODE_SERVER
-- 단계에서는 저장만 되고 실행에는 관여하지 않는다 — 마이그레이션을 두 번 하지 않으려 미리 둔다.
CREATE TABLE preview_runtime_configs (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '식별자',
    project_id      BIGINT       NOT NULL COMMENT '소속 프로젝트 (프로젝트당 1행)',
    runtime_type    VARCHAR(20)  NOT NULL COMMENT 'STATIC | NODE_SERVER | JAVA_FULLSTACK',
    start_command   VARCHAR(255) NULL COMMENT '서버형 실행 명령. NODE_SERVER 기본 npm start. 미지정 시 기본값',
    api_path_prefix VARCHAR(64)  NOT NULL DEFAULT '/api' COMMENT 'JAVA_FULLSTACK 내부 nginx 가 BE 로 보낼 접두사',
    health_path     VARCHAR(255) NULL COMMENT '준비 확인 경로(선택). 없으면 포트 응답만으로 판단',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_preview_runtime_config_project UNIQUE (project_id),
    CONSTRAINT fk_preview_runtime_config_project FOREIGN KEY (project_id)
        REFERENCES projects (project_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
