package com.example.dvely.agent.infrastructure.external;

import com.example.dvely.agent.application.port.out.DeployedHostingTargetPort;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link DeployedHostingTargetPort} 구현 — provisioning 의 RUNNING 서버로 도메인 대상을 유추한다.
 *
 * <p>RUNNING 백엔드(webOnly=false) 서버가 있으면 {@code AWS}(백엔드 EC2), RUNNING 독립 프론트
 * (webOnly=true)면 {@code AWS_EC2_FRONTEND}. 백엔드를 프론트보다 우선한다 — 사용자가 "이 앱"이라 할
 * 때는 대개 방금 배포한 백엔드를 뜻하기 때문이다. RUNNING EC2 서버가 없으면 empty(호출부가
 * GitHub Pages 등 기존 기본값으로 폴백한다). S3 정적 프론트는 여기서 다루지 않는다 — EC2 가 아니라
 * empty 로 떨어지고, S3 도메인은 별도 대상 지정 경로를 쓴다.</p>
 */
@Component
@RequiredArgsConstructor
public class ProvisionedServerDeployedHostingTargetAdapter implements DeployedHostingTargetPort {

    private final ProvisionedServerRepository serverRepository;

    @Override
    public Optional<DomainHostingTarget> resolveDeployedHostingTarget(Long projectId) {
        List<ProvisionedServer> running = serverRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(server -> server.getStatus() == ServerStatus.RUNNING)
                .toList();
        if (running.stream().anyMatch(server -> !server.isWebOnly())) {
            return Optional.of(DomainHostingTarget.AWS);                // RUNNING 백엔드 EC2
        }
        if (running.stream().anyMatch(ProvisionedServer::isWebOnly)) {
            return Optional.of(DomainHostingTarget.AWS_EC2_FRONTEND);   // RUNNING 독립 프론트 EC2
        }
        return Optional.empty();
    }
}
