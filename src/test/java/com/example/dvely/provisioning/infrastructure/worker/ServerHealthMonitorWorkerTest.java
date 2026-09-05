package com.example.dvely.provisioning.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.TcpHealthChecker;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServerHealthMonitorWorkerTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @Mock private TcpHealthChecker healthChecker;
    @InjectMocks private ServerHealthMonitorWorker worker;

    private ProvisionedServer running(String host) {
        LocalDateTime now = LocalDateTime.now();
        return new ProvisionedServer(1L, 7L, "t3.micro", ServerStatus.RUNNING,
                5L, "i-1", host, 8080, null, null, null, now, now);
    }

    @Test
    void healthyServer_recordsHealthyTrue() {
        ProvisionedServer server = running("1.2.3.4");
        when(serverRepository.findByStatus(eq(ServerStatus.RUNNING), anyInt())).thenReturn(List.of(server));
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(true);

        worker.monitorRunningServers();

        assertThat(server.getHealthy()).isTrue();
        assertThat(server.getLastHealthCheckAt()).isNotNull();
        verify(serverRepository).save(server);
    }

    @Test
    void appDown_recordsUnhealthy_withoutTerminating() {
        ProvisionedServer server = running("1.2.3.4");
        server.recordHealthCheck(true);   // 직전엔 건강했다
        when(serverRepository.findByStatus(eq(ServerStatus.RUNNING), anyInt())).thenReturn(List.of(server));
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);   // 이제 포트 무응답

        worker.monitorRunningServers();

        assertThat(server.getHealthy()).isFalse();          // 앱 무응답으로 표시
        assertThat(server.getStatus()).isEqualTo(ServerStatus.RUNNING);   // 인스턴스는 종료 안 함
        verify(serverRepository).save(server);
    }

    @Test
    void noPublicHost_skipped() {
        ProvisionedServer server = running(null);
        when(serverRepository.findByStatus(eq(ServerStatus.RUNNING), anyInt())).thenReturn(List.of(server));

        worker.monitorRunningServers();

        verify(healthChecker, never()).isHealthy(org.mockito.ArgumentMatchers.any(), anyInt());
        verify(serverRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
