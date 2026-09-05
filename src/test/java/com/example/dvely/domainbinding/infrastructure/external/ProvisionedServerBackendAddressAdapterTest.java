package com.example.dvely.domainbinding.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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

@ExtendWith(MockitoExtension.class)
class ProvisionedServerBackendAddressAdapterTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @InjectMocks private ProvisionedServerBackendAddressAdapter adapter;

    private ProvisionedServer running(Long id, String host, boolean webOnly) {
        LocalDateTime now = LocalDateTime.now();
        ProvisionedServer s = new ProvisionedServer(id, 7L, "t3.micro", ServerStatus.RUNNING,
                null, "i-" + id, host, 8080, null, null, null, now, now);
        s.assignWebOnly(webOnly);
        return s;
    }

    @Test
    void splitsBackendAndFrontendByWebOnlyFlag() {
        ProvisionedServer backend = running(1L, "10.0.0.1", false);
        ProvisionedServer frontend = running(2L, "10.0.0.2", true);
        when(serverRepository.findByProjectIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(frontend, backend));   // 순서 무관, 플래그로 가른다

        assertThat(adapter.resolveRunningBackendIp(7L)).contains("10.0.0.1");
        assertThat(adapter.resolveRunningFrontendHost(7L)).contains("10.0.0.2");
    }

    @Test
    void frontendOnlyProject_doesNotLeakFrontendEipAsBackend() {
        // 프론트(webOnly) 서버만 떠 있으면 "백엔드 IP" 는 없어야 한다 — 안 그러면 백엔드 도메인이 프론트를
        // 가리키는 오분류가 생긴다(이 분리 전의 버그).
        ProvisionedServer frontend = running(2L, "10.0.0.2", true);
        when(serverRepository.findByProjectIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(frontend));

        assertThat(adapter.resolveRunningBackendIp(7L)).isEmpty();
        assertThat(adapter.resolveRunningFrontendHost(7L)).contains("10.0.0.2");
    }

    @Test
    void ignoresNonRunningServers() {
        LocalDateTime now = LocalDateTime.now();
        ProvisionedServer queued = new ProvisionedServer(3L, 7L, "t3.micro", ServerStatus.PROVISIONING,
                null, "i-3", null, 8080, null, null, null, now, now);
        when(serverRepository.findByProjectIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(queued));

        assertThat(adapter.resolveRunningBackendIp(7L)).isEmpty();
        assertThat(adapter.resolveRunningFrontendHost(7L)).isEmpty();
    }
}
