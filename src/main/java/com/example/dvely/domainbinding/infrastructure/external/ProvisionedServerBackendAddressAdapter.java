package com.example.dvely.domainbinding.infrastructure.external;

import com.example.dvely.domainbinding.application.port.out.BackendAddressPort;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * BackendAddressPort 구현 — provisioning 의 서버 목록을 읽어 RUNNING 서버의 공개 IP 를 돌려준다.
 * 도메인바인딩(응용)은 포트만 알고, 이 인프라 어댑터가 두 도메인 사이 다리를 놓는다.
 */
@Component
@RequiredArgsConstructor
public class ProvisionedServerBackendAddressAdapter implements BackendAddressPort {

    private final ProvisionedServerRepository serverRepository;

    @Override
    public Optional<String> resolveRunningBackendIp(Long projectId) {
        return serverRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(s -> s.getStatus() == ServerStatus.RUNNING)
                .map(ProvisionedServer::getPublicHost)
                .filter(h -> h != null && !h.isBlank())
                .findFirst();
    }
}
