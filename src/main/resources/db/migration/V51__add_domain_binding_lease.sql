-- 다중 인스턴스에서 S3 CDN 프로비저닝 워커(S3CdnProvisionWorker)가 같은 도메인에 대해 CloudFront 배포·
-- ACM 인증서를 두 인스턴스에서 동시에 만들지 않도록(중복 배포/인증서 = 비용·고아), 진행 전에 행을 리스로
-- claim 한다. provisioned_servers 의 lease 와 동형. 순수 인프라 컬럼 — updateFrom 이 안 건드려 저장 시 보존.
ALTER TABLE domain_bindings
    ADD COLUMN lease_owner VARCHAR(100) NULL,
    ADD COLUMN lease_until DATETIME NULL AFTER lease_owner;
