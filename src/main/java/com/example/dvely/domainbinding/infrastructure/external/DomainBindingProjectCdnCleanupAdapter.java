package com.example.dvely.domainbinding.infrastructure.external;

import com.example.dvely.domainbinding.application.command.DomainBindingCommandService;
import com.example.dvely.project.application.port.out.ProjectCdnCleanupPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link ProjectCdnCleanupPort} 구현 — domainbinding 의 시스템 정리(cleanupProjectS3Domains)를 호출해
 * 다리를 놓는다. project(응용)는 포트만 알고, 이 인프라 어댑터가 두 도메인 사이를 잇는다. 정리 자체가
 * 도메인별 best-effort 지만, 상위 호출(예: findByProjectId)까지 방어해 프로젝트 삭제를 절대 안 되돌리게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainBindingProjectCdnCleanupAdapter implements ProjectCdnCleanupPort {

    private final DomainBindingCommandService domainBindingCommandService;

    @Override
    public void cleanupFrontendCdnDomains(Long projectId) {
        try {
            domainBindingCommandService.cleanupProjectS3Domains(projectId);
        } catch (RuntimeException e) {
            log.warn("프로젝트 삭제 시 S3 CDN 도메인 정리 실패(무시): projectId={} 원인={}", projectId, e.toString());
        }
    }
}
