-- 다중 인스턴스 배포에서 재배포 교체 워커(ServerReplacementService)가 같은 서버의 EIP 재연결·종료를
-- 두 인스턴스에서 동시에 하지 않도록, 처리 전에 행을 리스로 claim 한다(Agent 도메인의 lease_owner/
-- lease_until 패턴과 동형). NULL=미claim. 만료(lease_until < now)면 다른 인스턴스가 이어받는다.
-- 도메인 모델에는 싣지 않는다 — 순수 인프라 컬럼이라 applyFrom 이 안 건드려 저장 시 보존된다.
ALTER TABLE provisioned_servers
    ADD COLUMN lease_owner VARCHAR(100) NULL AFTER recovery_attempted_at,
    ADD COLUMN lease_until DATETIME NULL AFTER lease_owner;
