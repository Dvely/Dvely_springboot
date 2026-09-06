-- S3 프론트 HTTPS(AWS_S3_FRONTEND) = CloudFront + ACM 프로비저닝 자원 식별자.
-- 다른 호스팅 타깃(GitHub Pages·EC2)은 전부 NULL 이라 하위호환. 비동기 워커가 단계별로 채운다.
ALTER TABLE domains
    ADD COLUMN acm_certificate_arn        VARCHAR(2048) NULL AFTER last_checked_at,
    ADD COLUMN acm_validation_record_id   VARCHAR(100)  NULL AFTER acm_certificate_arn,
    ADD COLUMN cloudfront_distribution_id VARCHAR(64)   NULL AFTER acm_validation_record_id;

-- CloudFront 배포 정리(리프) 큐. 배포 삭제는 disable → Deployed 대기 → delete 의 다단계·수 분 작업이라
-- 도메인 삭제 시점에 동기로 못 끝낸다. 삭제 시 이 큐에 넣고, 리퍼 워커가 Deployed 되면 배포·인증서를
-- 지운다(고아 자원 방지). 도메인 행과 분리해 도메인은 즉시 하드삭제하고 정리만 뒤로 미룬다.
CREATE TABLE cdn_deletions (
    deletion_id         BIGINT        NOT NULL AUTO_INCREMENT,
    cloud_connection_id BIGINT        NOT NULL COMMENT '삭제에 쓸 assume-role 자격의 연결',
    distribution_id     VARCHAR(64)   NOT NULL COMMENT '지울 CloudFront 배포 id',
    certificate_arn     VARCHAR(2048) NULL     COMMENT '배포 삭제 후 지울 ACM 인증서 ARN(us-east-1)',
    hostname            VARCHAR(255)  NULL     COMMENT '로깅·추적용',
    last_error          VARCHAR(512)  NULL     COMMENT '마지막 리프 시도 실패 사유(있으면)',
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (deletion_id)
);
