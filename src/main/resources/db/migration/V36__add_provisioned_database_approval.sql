-- RDS 등 승인 대상 프로비저닝의 승인↔자원 연결. 커맨드서비스가 pending 행을 만들며 approval_id 를
-- 채우고, 승인 핸들러가 이 값으로 대상 행을 찾아 실제 생성을 시작한다. LOCAL·PREVIEW_AUTO 는 승인이
-- 없으므로 null 이다.
ALTER TABLE provisioned_databases
    ADD COLUMN approval_id BIGINT NULL COMMENT '승인 대상(RDS 등) 연결. LOCAL/자동은 null',
    ADD KEY idx_provisioned_databases_approval (approval_id);
