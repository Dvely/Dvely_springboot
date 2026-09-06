package com.example.dvely.provisioning.infrastructure.external;

import com.example.dvely.domainbinding.application.command.DomainBindingCommandService;
import com.example.dvely.provisioning.application.port.out.ProjectDomainCleanupPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ProjectDomainCleanupPort 구현 — domainbinding 의 시스템 정리(releaseServerDomains)를 호출해 다리를
 * 놓는다. provisioning(응용)은 포트만 알고, 이 인프라 어댑터가 두 도메인 사이를 잇는다.
 */
@Component
@RequiredArgsConstructor
public class DomainBindingProjectDomainCleanupAdapter implements ProjectDomainCleanupPort {

    private final DomainBindingCommandService domainBindingCommandService;

    @Override
    public void releaseServerDomains(Long projectId, String ipAddress) {
        domainBindingCommandService.releaseServerDomains(projectId, ipAddress);
    }
}
