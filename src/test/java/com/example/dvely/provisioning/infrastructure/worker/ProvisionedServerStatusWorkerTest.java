package com.example.dvely.provisioning.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner.Ec2InstanceStatus;
import com.example.dvely.provisioning.infrastructure.TcpHealthChecker;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProvisionedServerStatusWorkerTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private Ec2Provisioner ec2;
    @Mock private TcpHealthChecker healthChecker;

    @InjectMocks private ProvisionedServerStatusWorker worker;

    private static final Long CONN_ID = 11L;

    private ProvisionedServer provisioning(LocalDateTime updatedAt) {
        return new ProvisionedServer(1L, 10L, "t3.micro", ServerStatus.PROVISIONING,
                CONN_ID, "i-123", null, 8080, 99L, null, null, LocalDateTime.now(), updatedAt);
    }

    private void stubBatch(ProvisionedServer s) {
        when(serverRepository.findByStatus(ServerStatus.PROVISIONING, 20)).thenReturn(List.of(s));
        when(cloudConnectionRepository.findById(CONN_ID)).thenReturn(Optional.of(connection()));
    }

    @Test
    void runningAndHealthyBecomesRunning() {
        stubBatch(provisioning(LocalDateTime.now()));
        when(ec2.describe(any(), eq("i-123"))).thenReturn(new Ec2InstanceStatus("running", "ec2-host"));
        when(healthChecker.isHealthy("ec2-host", 8080)).thenReturn(true);

        worker.pollProvisioning();

        ArgumentCaptor<ProvisionedServer> saved = ArgumentCaptor.forClass(ProvisionedServer.class);
        verify(serverRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ServerStatus.RUNNING);
        assertThat(saved.getValue().getPublicHost()).isEqualTo("ec2-host");
    }

    @Test
    void terminatedInstanceBecomesFailed() {
        stubBatch(provisioning(LocalDateTime.now()));
        when(ec2.describe(any(), eq("i-123"))).thenReturn(new Ec2InstanceStatus("terminated", null));

        worker.pollProvisioning();

        ArgumentCaptor<ProvisionedServer> saved = ArgumentCaptor.forClass(ProvisionedServer.class);
        verify(serverRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ServerStatus.FAILED);
    }

    @Test
    void runningButNotHealthyKeepsWaiting() {
        stubBatch(provisioning(LocalDateTime.now()));   // 방금 시작 — 타임아웃 아님
        when(ec2.describe(any(), eq("i-123"))).thenReturn(new Ec2InstanceStatus("running", "ec2-host"));
        when(healthChecker.isHealthy("ec2-host", 8080)).thenReturn(false);

        worker.pollProvisioning();

        verify(serverRepository, never()).save(any());   // 저장 안 함 — 다음 주기에 다시
        verify(ec2, never()).terminate(any(), any());
    }

    @Test
    void bootTimeoutTerminatesAndFails() {
        stubBatch(provisioning(LocalDateTime.now().minusMinutes(21)));   // 기동 타임아웃
        when(ec2.describe(any(), eq("i-123"))).thenReturn(new Ec2InstanceStatus("running", "ec2-host"));
        when(healthChecker.isHealthy("ec2-host", 8080)).thenReturn(false);

        worker.pollProvisioning();

        verify(ec2).terminate(any(), eq("i-123"));   // 과금 멈추려 정리
        ArgumentCaptor<ProvisionedServer> saved = ArgumentCaptor.forClass(ProvisionedServer.class);
        verify(serverRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ServerStatus.FAILED);
    }

    private CloudConnection connection() {
        return new CloudConnection(CONN_ID, 7L, CloudProvider.AWS, "production", "123456789012",
                "ap-northeast-2", null, "ACCESS_KEY", "AKIA1234567890ABCDEF",
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD", null, null, null, null, null,
                CloudConnectionStatus.CONNECTED, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }
}
