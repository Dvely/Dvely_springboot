-- 다중 인스턴스에서 CloudFront 리프 워커(CdnDeletionReaper)가 같은 배포를 두 인스턴스에서 동시에
-- disable·delete 하지 않도록(중복·느린 CloudFront 호출·rate limit 방지), 정리 전에 행을 리스로 claim 한다.
-- provisioned_servers·domains 의 lease 와 동형. 리퍼는 도메인 모델 없이 엔티티를 직접 다루지만, 실패 시
-- 기록도 targeted UPDATE(recordError)로 해서 이 lease 를 덮어쓰지 않는다.
ALTER TABLE cdn_deletions
    ADD COLUMN lease_owner VARCHAR(100) NULL,
    ADD COLUMN lease_until DATETIME NULL AFTER lease_owner;
