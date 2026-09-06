package com.example.dvely.domainbinding.infrastructure.external;

import com.example.dvely.domainbinding.application.port.out.BackendAddressPort;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
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
        return resolveRunningServer(projectId, false).map(ProvisionedServer::getPublicHost);
    }

    @Override
    public Optional<String> resolveRunningFrontendHost(Long projectId) {
        return resolveRunningServer(projectId, true).map(ProvisionedServer::getPublicHost);
    }

    @Override
    public Optional<Long> resolveServerId(Long projectId, DomainHostingTarget hostingTarget) {
        // 도메인이 어느 서버를 가리키는지는 hostingTarget 이 정한다. EC2 대상만 가리킬 서버가 있다.
        Boolean webOnly = switch (hostingTarget) {
            case AWS_EC2_FRONTEND -> true;    // 독립 프론트(webOnly)
            case AWS -> false;                // 백엔드
            default -> null;                  // GitHub Pages·S3·GCP = 가리킬 EC2 서버 없음
        };
        if (webOnly == null) {
            return Optional.empty();
        }
        return resolveRunningServer(projectId, webOnly).map(ProvisionedServer::getId);
    }

    /**
     * RUNNING 서버 중 webOnly 가 일치하고 공개 host 가 채워진 첫(최신) 서버. 백엔드/프론트를 이 플래그로
     * 가른다. host·serverId 를 모두 이 하나로 뽑으므로 둘은 항상 같은 서버를 가리킨다.
     */
    private Optional<ProvisionedServer> resolveRunningServer(Long projectId, boolean webOnly) {
        return serverRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(s -> s.getStatus() == ServerStatus.RUNNING)
                .filter(s -> s.isWebOnly() == webOnly)
                .filter(s -> s.getPublicHost() != null && !s.getPublicHost().isBlank())
                .findFirst();
    }
}
