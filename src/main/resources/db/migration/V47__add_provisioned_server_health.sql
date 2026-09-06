-- RUNNING 이후 앱 건강 상태(주기 TCP 헬스체크 결과). status(인스턴스 수준)와 별개로, 인스턴스는
-- RUNNING 인데 앱이 죽으면 healthy=FALSE 로 드러낸다(종료하지 않음). NULL=아직 미확인. 기존 서버는
-- 다음 헬스 모니터 주기에 채워진다(하위호환).
ALTER TABLE provisioned_servers
    ADD COLUMN healthy BOOLEAN NULL AFTER public_host,
    ADD COLUMN last_health_check_at DATETIME NULL AFTER healthy;
