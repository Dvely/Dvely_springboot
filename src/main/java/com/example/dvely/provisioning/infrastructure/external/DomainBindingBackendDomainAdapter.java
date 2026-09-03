package com.example.dvely.provisioning.infrastructure.external;

import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.provisioning.application.port.out.BackendDomainPort;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * BackendDomainPort 구현 — domainbinding 을 읽어 프로젝트 백엔드(AWS 대상)에 연결된 CONNECTED 도메인을
 * 돌려준다. provisioning(응용)은 포트만 알고, 이 인프라 어댑터가 두 도메인 사이 다리를 놓는다.
 */
@Component
@RequiredArgsConstructor
public class DomainBindingBackendDomainAdapter implements BackendDomainPort {

    private final DomainBindingRepository domainBindingRepository;

    @Override
    public Optional<String> resolveConnectedBackendDomain(Long projectId) {
        return domainBindingRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(d -> d.getHostingTarget() == DomainHostingTarget.AWS)
                .filter(d -> d.getStatus() == DomainStatus.CONNECTED)
                .map(DomainBinding::getHostname)
                .findFirst();
    }
}
