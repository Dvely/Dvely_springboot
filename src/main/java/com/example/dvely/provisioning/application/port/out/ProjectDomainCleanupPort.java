package com.example.dvely.provisioning.application.port.out;

/**
 * 백엔드 서버 종료 시 그 서버 IP 를 가리키던 프로젝트 도메인을 정리하는 포트. 해제된 EIP 를 가리킨 채
 * 남은 Cloudflare 레코드는 dangling DNS(서브도메인 탈취)가 되므로 반드시 지운다. 구현은 인프라 계층에서
 * domainbinding 을 호출한다 — provisioning 응용은 이 포트에만 의존한다(도메인 경계 유지).
 */
public interface ProjectDomainCleanupPort {

    /** projectId 의 백엔드(AWS) 도메인 중 ipAddress 를 가리키던 것을 정리한다(Cloudflare 레코드+행 제거). */
    void releaseBackendDomains(Long projectId, String ipAddress);
}
