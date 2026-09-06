-- 부트 타임아웃으로 실패·종료할 때, terminate 직전에 뜬 부트 로그(cloud-init) 스냅샷을 보존한다.
-- 인스턴스가 사라진 뒤에도 "왜 안 떴나"를 사용자가 볼 수 있게 한다. 정상 기동 서버는 NULL(라이브
-- 조회로 충분). 최근 ~200줄이라 길어서 TEXT.
ALTER TABLE provisioned_servers
    ADD COLUMN boot_diagnostics TEXT NULL AFTER last_health_check_at;
