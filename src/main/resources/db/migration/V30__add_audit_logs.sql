SET NAMES utf8mb4;

-- Issue #74 (design ad-audit-log-design.md §2.1): cross-cutting append-only audit trail for
-- GitHub/deployment/domain/infra actions (BI-188~192). Deliberately has NO foreign keys (ADR-A3):
-- this table records facts about resources, not live references to them, and must survive the
-- referenced row's deletion (e.g. a deleted domain_binding — DOMAIN_DELETED is its only surviving
-- trace). Omitting FKs also keeps this table out of the InnoDB parent-row S-lock path that
-- `projects` FK checks would otherwise pull it into (see design F10) — this table is a leaf in the
-- lock hierarchy (#55 ADR-Y1), never a participant in another transaction's lock wait.
CREATE TABLE audit_logs (
    audit_log_id  BIGINT       NOT NULL AUTO_INCREMENT,
    category      VARCHAR(20)  NOT NULL COMMENT 'GITHUB | DEPLOYMENT | DOMAIN | INFRA',
    action        VARCHAR(40)  NOT NULL COMMENT 'AuditAction enum 이름 (design §3.1 카탈로그)',
    outcome       VARCHAR(20)  NOT NULL COMMENT 'SUCCEEDED | FAILED',
    actor_type    VARCHAR(20)  NOT NULL COMMENT 'USER | AGENT | SYSTEM',
    actor_user_id BIGINT       NULL     COMMENT '행위 귀속 사용자. SYSTEM 이벤트도 소유자 추적 가능하면 세팅',
    project_id    BIGINT       NULL     COMMENT '이번 단위 기록 지점은 전부 값 세팅. 계정 수준 이벤트 대비 NULL 허용. FK 없음(ADR-A3)',
    resource_type VARCHAR(30)  NULL     COMMENT 'REPOSITORY | DEPLOYMENT | DOMAIN_BINDING | PREVIEW_SESSION | INFRA_CONFIG_CHANGE | CHANGE',
    resource_id   VARCHAR(255) NULL     COMMENT '대상 식별자 (숫자 PK 또는 repo full name 등 자연키 문자열화)',
    task_id       VARCHAR(64)  NULL     COMMENT 'agent task 상관관계 (agent_runs.task_id 값 — FK 아님)',
    approval_id   BIGINT       NULL     COMMENT '승인 상관관계 (approvals.approval_id 값 — FK 아님)',
    detail        VARCHAR(1000) NULL    COMMENT '코드가 조립한 화이트리스트 요약. 토큰/시크릿/환경변수 값/로그 본문 저장 금지(design §7)',
    error_summary VARCHAR(500) NULL     COMMENT '실패 시 예외 요약 — SecretRedactor 적용 + 500자 절단 후 저장',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (audit_log_id),
    KEY idx_audit_logs_project (project_id, audit_log_id),
    KEY idx_audit_logs_project_category (project_id, category, audit_log_id),
    KEY idx_audit_logs_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='통합 감사 로그 — append-only, FK 없음, 도메인 이력 테이블의 횡단 보완(ADR-A1). UPDATE/DELETE는 retention 스케줄러만';
