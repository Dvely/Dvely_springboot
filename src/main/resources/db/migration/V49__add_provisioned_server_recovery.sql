-- 앱이 무응답(healthy=false)일 때 자동복구(재시작)를 시도한 시각. 한 장애 에피소드당 1회만
-- 시도하려는 표시다 — 회복되면(healthy=true) 헬스 모니터가 NULL 로 지운다. NULL=이번 무응답에
-- 아직 복구를 안 시도함. 값이 있고 여전히 무응답이면 "복구를 시도했으나 회복 못 함".
ALTER TABLE provisioned_servers
    ADD COLUMN recovery_attempted_at DATETIME NULL AFTER boot_diagnostics;
