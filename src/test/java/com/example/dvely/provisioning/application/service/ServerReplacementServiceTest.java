package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServerReplacementServiceTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private Ec2Provisioner ec2;
    @InjectMocks private ServerReplacementService service;

    private ProvisionedServer server(Long id, String instanceId, String publicHost, boolean webOnly) {
        LocalDateTime now = LocalDateTime.now();
        ProvisionedServer s = new ProvisionedServer(id, 7L, "t3.micro", ServerStatus.RUNNING,
                5L, instanceId, publicHost, 8080, null, null, null, now, now);
        s.assignWebOnly(webOnly);
        return s;
    }

    private ProvisionedServer serverWith(Long id, ServerStatus status) {
        LocalDateTime now = LocalDateTime.now();
        ProvisionedServer s = new ProvisionedServer(id, 7L, "t3.micro", status,
                5L, "i-" + id, null, 8080, null, null, null, now, now);
        s.assignWebOnly(false);
        return s;
    }

    @Test
    void movesEipToNewInstance_releasesNewOwnEip_terminatesOld_clearsSupersedes() {
        ProvisionedServer oldServer = server(1L, "i-old", "2.2.2.2", false);
        oldServer.assignElasticIp("alloc-old");
        ProvisionedServer newServer = server(2L, "i-new", "1.1.1.1", false);
        newServer.assignElasticIp("alloc-new");
        newServer.assignSupersedes(1L);
        when(serverRepository.findRunningWithPendingReplacement(anyInt())).thenReturn(List.of(newServer));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(oldServer));
        when(cloudConnectionRepository.findById(5L)).thenReturn(Optional.of(mock(CloudConnection.class)));

        service.completePendingReplacements();

        // 옛 EIP 를 새 인스턴스로 이동, 새 서버의 자기 EIP 는 release.
        verify(ec2).reassociateElasticIp(org.mockito.ArgumentMatchers.any(), eq("alloc-old"), eq("i-new"));
        verify(ec2).releaseElasticIp(org.mockito.ArgumentMatchers.any(), eq("alloc-new"));
        // 새 서버가 옛 EIP·주소를 넘겨받는다(dnsTarget=IP 불변 → 도메인 그대로).
        assertThat(newServer.getElasticIpAllocationId()).isEqualTo("alloc-old");
        assertThat(newServer.getPublicHost()).isEqualTo("2.2.2.2");
        assertThat(newServer.getSupersedesServerId()).isNull();   // 완료 표시 제거
        // 옛 서버는 EIP·주소 분리 후 인스턴스 종료(공유 자원 정리 안 함).
        assertThat(oldServer.getElasticIpAllocationId()).isNull();
        assertThat(oldServer.getPublicHost()).isNull();
        assertThat(oldServer.getStatus()).isEqualTo(ServerStatus.TERMINATED);
        verify(ec2).terminate(org.mockito.ArgumentMatchers.any(), eq("i-old"));
    }

    @Test
    void oldAlreadyTerminated_justClearsSupersedes_noEc2() {
        ProvisionedServer oldServer = server(1L, "i-old", null, false);
        oldServer.markTerminated();
        ProvisionedServer newServer = server(2L, "i-new", "1.1.1.1", false);
        newServer.assignSupersedes(1L);
        when(serverRepository.findRunningWithPendingReplacement(anyInt())).thenReturn(List.of(newServer));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(oldServer));

        service.completePendingReplacements();

        assertThat(newServer.getSupersedesServerId()).isNull();
        verify(ec2, never()).reassociateElasticIp(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(ec2, never()).terminate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void oldHasNoEip_skipsMove_stillTerminatesOld() {
        ProvisionedServer oldServer = server(1L, "i-old", "2.2.2.2", true);   // EIP 없음(비정상/과거 상태)
        ProvisionedServer newServer = server(2L, "i-new", "1.1.1.1", true);
        newServer.assignElasticIp("alloc-new");
        newServer.assignSupersedes(1L);
        when(serverRepository.findRunningWithPendingReplacement(anyInt())).thenReturn(List.of(newServer));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(oldServer));
        when(cloudConnectionRepository.findById(5L)).thenReturn(Optional.of(mock(CloudConnection.class)));

        service.completePendingReplacements();

        verify(ec2, never()).reassociateElasticIp(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(ec2).terminate(org.mockito.ArgumentMatchers.any(), eq("i-old"));
        assertThat(oldServer.getStatus()).isEqualTo(ServerStatus.TERMINATED);
        assertThat(newServer.getSupersedesServerId()).isNull();
        // 새 서버는 자기 EIP 유지(옮길 옛 EIP 가 없음).
        assertThat(newServer.getElasticIpAllocationId()).isEqualTo("alloc-new");
    }

    // ── 재배포 체인(더블 재배포) ────────────────────────────────────────────

    @Test
    void oldStillProvisioning_waits_keepsSupersedes() {
        // 교체 대상(직전 재배포)이 아직 뜨는 중 → 그 서버가 먼저 라이브 EIP 를 넘겨받게 두고 대기한다.
        ProvisionedServer oldServer = serverWith(1L, ServerStatus.PROVISIONING);
        ProvisionedServer newServer = server(2L, "i-new", "1.1.1.1", false);
        newServer.assignSupersedes(1L);
        when(serverRepository.findRunningWithPendingReplacement(anyInt())).thenReturn(List.of(newServer));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(oldServer));

        service.completePendingReplacements();

        assertThat(newServer.getSupersedesServerId()).isEqualTo(1L);   // 지우지 않고 다음 주기에 다시
        verify(ec2, never()).terminate(any(), any());
        verify(ec2, never()).reassociateElasticIp(any(), any(), any());
    }

    @Test
    void oldRunningButNotSettled_waits() {
        // 교체 대상이 RUNNING 이지만 자기 교체를 아직 안 끝냄(supersedes 남음) → 정착까지 대기.
        // 이 가드로 같은 주기 안에서 C 를 B 보다 먼저 처리해도 안전하다.
        ProvisionedServer oldServer = server(1L, "i-old", "2.2.2.2", false);
        oldServer.assignElasticIp("alloc-old");
        oldServer.assignSupersedes(99L);
        ProvisionedServer newServer = server(2L, "i-new", "1.1.1.1", false);
        newServer.assignSupersedes(1L);
        when(serverRepository.findRunningWithPendingReplacement(anyInt())).thenReturn(List.of(newServer));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(oldServer));

        service.completePendingReplacements();

        assertThat(newServer.getSupersedesServerId()).isEqualTo(1L);   // 대기
        verify(ec2, never()).terminate(any(), any());
        verify(ec2, never()).reassociateElasticIp(any(), any(), any());
    }

    @Test
    void oldFailed_chainWalksToItsTarget() {
        // 직전 재배포가 실패 → 그 서버가 가리키던 진짜 라이브 서버로 승계(체인 워크). 다음 주기에 99 를 교체.
        ProvisionedServer oldServer = serverWith(1L, ServerStatus.FAILED);
        oldServer.assignSupersedes(99L);
        ProvisionedServer newServer = server(2L, "i-new", "1.1.1.1", false);
        newServer.assignSupersedes(1L);
        when(serverRepository.findRunningWithPendingReplacement(anyInt())).thenReturn(List.of(newServer));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(oldServer));

        service.completePendingReplacements();

        assertThat(newServer.getSupersedesServerId()).isEqualTo(99L);   // 승계
        verify(ec2, never()).terminate(any(), any());
    }

    @Test
    void oldFailed_noTarget_clearsSupersedes() {
        // 직전 재배포가 실패했고 그마저 교체 대상이 없다(체인 전체가 종착) → 표시만 지운다.
        ProvisionedServer oldServer = serverWith(1L, ServerStatus.FAILED);
        ProvisionedServer newServer = server(2L, "i-new", "1.1.1.1", false);
        newServer.assignSupersedes(1L);
        when(serverRepository.findRunningWithPendingReplacement(anyInt())).thenReturn(List.of(newServer));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(oldServer));

        service.completePendingReplacements();

        assertThat(newServer.getSupersedesServerId()).isNull();
        verify(ec2, never()).terminate(any(), any());
    }
}
