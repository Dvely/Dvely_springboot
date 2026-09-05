package com.example.dvely.project.application.port.out;

/**
 * 프로젝트가 사라질 때 그 프로젝트의 S3 프론트 HTTPS(CloudFront+ACM) 도메인을 정리하는 포트. 도메인 행은
 * 프로젝트 삭제 트랜잭션이 지우지 않으므로, 삭제 후 이 포트로 Cloudflare 레코드 제거 + CloudFront/ACM 정리
 * 큐잉을 한다(고아 CloudFront 배포·공개 DNS 방지). 구현은 domainbinding 도메인(도메인 바인딩·CDN 배관 보유).
 */
public interface ProjectCdnCleanupPort {

    /**
     * 프로젝트의 AWS_S3_FRONTEND 도메인을 정리한다(없으면 no-op). <b>best-effort</b> — 정리 실패가 이미
     * 끝난 프로젝트 삭제를 되돌리면 안 된다.
     */
    void cleanupFrontendCdnDomains(Long projectId);
}
