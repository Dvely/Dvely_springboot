package com.example.dvely.provisioning.infrastructure.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrphanElasticIpSweeperTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private Ec2Provisioner ec2;

    @InjectMocks private OrphanElasticIpSweeper sweeper;

    @Test
    void releasesUnassociatedOrphanButKeepsAssociatedAndRunningOwned() {
        CloudConnection conn = Mockito.mock(CloudConnection.class);
        when(serverRepository.findDistinctCloudConnectionIds()).thenReturn(List.of(1L));
        when(serverRepository.existsInFlightByCloudConnectionId(1L)).thenReturn(false);
        when(cloudConnectionRepository.findById(1L)).thenReturn(Optional.of(conn));
        when(serverRepository.findElasticIpAllocationIds(1L, ServerStatus.RUNNING))
                .thenReturn(List.of("eipalloc-live"));
        when(ec2.listQeployElasticIps(conn)).thenReturn(List.of(
                new Ec2Provisioner.QeployEip("eipalloc-orphan", "1.2.3.4", false), // 미연결·비소유 → 회수
                new Ec2Provisioner.QeployEip("eipalloc-live", "5.6.7.8", true),    // 연결됨 → 유지
                new Ec2Provisioner.QeployEip("eipalloc-assoc", "9.9.9.9", true))); // 연결됨 → 유지

        sweeper.sweep();

        verify(ec2).releaseElasticIp(conn, "eipalloc-orphan");
        verify(ec2, never()).releaseElasticIp(conn, "eipalloc-live");
        verify(ec2, never()).releaseElasticIp(conn, "eipalloc-assoc");
    }

    @Test
    void skipsConnectionWithInFlightDeploy() {
        // 배포 진행 중인 연결은 통째로 건너뛴다 — 방금 할당됐지만 아직 연결 전인 EIP 오회수 방지.
        when(serverRepository.findDistinctCloudConnectionIds()).thenReturn(List.of(1L));
        when(serverRepository.existsInFlightByCloudConnectionId(1L)).thenReturn(true);

        sweeper.sweep();

        verify(cloudConnectionRepository, never()).findById(any());
        verifyNoInteractions(ec2);
    }

    @Test
    void oneConnectionFailureDoesNotStopOthers() {
        CloudConnection conn2 = Mockito.mock(CloudConnection.class);
        when(serverRepository.findDistinctCloudConnectionIds()).thenReturn(List.of(1L, 2L));
        when(serverRepository.existsInFlightByCloudConnectionId(1L)).thenReturn(false);
        when(serverRepository.existsInFlightByCloudConnectionId(2L)).thenReturn(false);
        when(cloudConnectionRepository.findById(1L)).thenReturn(Optional.of(Mockito.mock(CloudConnection.class)));
        when(cloudConnectionRepository.findById(2L)).thenReturn(Optional.of(conn2));
        when(serverRepository.findElasticIpAllocationIds(eq(1L), any())).thenReturn(List.of());
        when(serverRepository.findElasticIpAllocationIds(eq(2L), any())).thenReturn(List.of());
        when(ec2.listQeployElasticIps(any())).thenThrow(new RuntimeException("describe denied"))
                .thenReturn(List.of(new Ec2Provisioner.QeployEip("eipalloc-orphan2", "2.2.2.2", false)));

        sweeper.sweep();

        // 1번 연결이 예외로 터져도 2번은 계속 훑어 고아를 회수한다.
        verify(ec2).releaseElasticIp(conn2, "eipalloc-orphan2");
    }
}
