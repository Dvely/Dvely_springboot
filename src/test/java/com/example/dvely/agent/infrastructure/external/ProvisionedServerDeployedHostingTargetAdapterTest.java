package com.example.dvely.agent.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 도메인 대상 기본값을 프로젝트 실제 배포 상태에서 유추 — 무조건 GitHub Pages 로 떨어져 EC2 배포 앱에
 * 도메인이 404 나던 회귀(배포 e2e 실측)를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class ProvisionedServerDeployedHostingTargetAdapterTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @InjectMocks private ProvisionedServerDeployedHostingTargetAdapter adapter;

    private ProvisionedServer running(Long id, boolean webOnly) {
        LocalDateTime now = LocalDateTime.now();
        ProvisionedServer s = new ProvisionedServer(id, 7L, "t3.micro", ServerStatus.RUNNING,
                null, "i-" + id, "10.0.0." + id, 8080, null, null, null, now, now);
        s.assignWebOnly(webOnly);
        return s;
    }

    @Test
    void runningBackendResolvesToAws() {
        when(serverRepository.findByProjectIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(running(1L, false)));   // RUNNING 백엔드(webOnly=false)

        assertThat(adapter.resolveDeployedHostingTarget(7L)).contains(DomainHostingTarget.AWS);
    }

    @Test
    void runningStandaloneFrontendResolvesToEc2Frontend() {
        when(serverRepository.findByProjectIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(running(2L, true)));     // RUNNING 독립 프론트(webOnly=true)

        assertThat(adapter.resolveDeployedHostingTarget(7L))
                .contains(DomainHostingTarget.AWS_EC2_FRONTEND);
    }

    /** 백엔드·프론트가 함께 떠 있으면 백엔드 우선("이 앱"은 대개 방금 배포한 백엔드). */
    @Test
    void prefersBackendWhenBothRunning() {
        when(serverRepository.findByProjectIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(running(2L, true), running(1L, false)));

        assertThat(adapter.resolveDeployedHostingTarget(7L)).contains(DomainHostingTarget.AWS);
    }

    /** RUNNING EC2 서버가 없으면(정적/Pages 프로젝트) empty — 호출부가 기존 기본값으로 폴백. */
    @Test
    void resolvesEmptyWhenNoRunningEc2Server() {
        LocalDateTime now = LocalDateTime.now();
        ProvisionedServer queued = new ProvisionedServer(3L, 7L, "t3.micro", ServerStatus.PROVISIONING,
                null, "i-3", null, 8080, null, null, null, now, now);
        when(serverRepository.findByProjectIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(queued));

        assertThat(adapter.resolveDeployedHostingTarget(7L)).isEmpty();
    }
}
