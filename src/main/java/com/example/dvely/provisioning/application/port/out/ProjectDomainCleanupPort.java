package com.example.dvely.provisioning.application.port.out;

/**
 * EC2 서버(백엔드·독립 프론트) 종료 시 그 서버 IP 를 가리키던 프로젝트 도메인을 정리하는 포트. 해제된
 * EIP 를 가리킨 채 남은 Cloudflare 레코드는 dangling DNS(서브도메인 탈취)가 되므로 반드시 지운다. 구현은
 * 인프라 계층에서 domainbinding 을 호출한다 — provisioning 응용은 이 포트에만 의존한다(도메인 경계 유지).
 */
public interface ProjectDomainCleanupPort {

    /**
     * projectId 의 EC2 도메인(백엔드 AWS · 독립 프론트 AWS_EC2_FRONTEND) 중 ipAddress 를 가리키던 것을
     * 정리한다(Cloudflare 레코드+행 제거). 종료된 서버가 백엔드든 프론트든 해제 EIP 기준으로 고른다.
     */
    void releaseServerDomains(Long projectId, String ipAddress);
}
