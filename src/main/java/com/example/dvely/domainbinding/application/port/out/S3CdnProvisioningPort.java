package com.example.dvely.domainbinding.application.port.out;

/**
 * S3 프론트 HTTPS(AWS_S3_FRONTEND) 를 위한 CloudFront + ACM 프로비저닝을 domainbinding 에 넘기는 포트.
 * 사용자 계정 자원(ACM 인증서·CloudFront 배포)을 다루므로 구현은 provisioning 인프라에서 프로젝트의 클라우드
 * 연결을 assume-role 로 해석해 호출한다 — domainbinding 응용 계층은 이 포트에만 의존한다(도메인 경계 유지).
 *
 * <p>프로비저닝은 비동기·다단계라(인증서 검증 → 배포 생성 → Deployed 대기, 수 분), 커맨드서비스가 인증서만
 * 요청해 PROVISIONING 으로 두고 워커가 나머지를 진행한다. 배포 정리(삭제)도 즉시 못 끝나 리퍼가 마무리한다.</p>
 */
public interface S3CdnProvisioningPort {

    /** 이 프로젝트의 S3 배포 연결로 hostname 용 ACM 인증서(us-east-1)를 발급 요청한다. 반환 certificate ARN. */
    String requestCertificate(Long projectId, String hostname);

    /** 인증서 상태 + DNS 검증 레코드(발급 직후엔 검증 레코드가 아직 null 일 수 있다). */
    AcmCertStatus describeCertificate(Long projectId, String certificateArn);

    /**
     * S3 website 엔드포인트를 오리진으로 CloudFront 배포를 만든다. 인증서는 ISSUED 여야 한다. 반환은 배포
     * id + CloudFront 도메인(dxxx.cloudfront.net) — 도메인은 최종 CNAME(사용자 도메인→이 값)의 대상이 된다.
     */
    CdnDistribution createDistribution(Long projectId, String hostname, String certificateArn);

    /**
     * 도메인/프로젝트 삭제 시 CloudFront 배포·인증서 정리를 큐잉한다(배포 disable 을 즉시 시도). 실제 삭제는
     * 배포가 Deployed 되어야 가능해 리퍼가 마무리한다. DNS 레코드(최종 CNAME·검증 CNAME) 제거는 호출부가
     * Cloudflare 로 직접 한다(여긴 AWS 자원만).
     */
    void scheduleDistributionCleanup(Long projectId, String distributionId,
                                     String certificateArn, String hostname);

    /**
     * 배포가 아직 없이 인증서만 발급된 상태에서 정리할 때, 인증서를 바로 삭제한다(배포에 안 붙어 있어 즉시
     * 삭제 가능). best-effort — 실패해도 던지지 않는다(인증서는 무료·소량).
     */
    void deleteCertificate(Long projectId, String certificateArn);

    record AcmCertStatus(String status,
                         String validationRecordName,
                         String validationRecordValue,
                         boolean issued,
                         boolean failed,
                         boolean hasValidationRecord) {}

    record CdnDistribution(String distributionId, String cloudfrontDomain) {}
}
