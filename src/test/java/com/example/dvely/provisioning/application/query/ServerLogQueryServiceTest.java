package com.example.dvely.provisioning.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.domain.value.ServerLogSource;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.SsmRunCommandClient;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServerLogQueryServiceTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private SsmRunCommandClient ssmRunCommandClient;
    @InjectMocks private ServerLogQueryService service;

    private ProvisionedServer running(ServerDeployMode mode) {
        LocalDateTime now = LocalDateTime.now();
        ProvisionedServer s = new ProvisionedServer(1L, 7L, "t3.micro", ServerStatus.RUNNING,
                5L, "i-123", "1.2.3.4", 8080, null, null, null, now, now);
        s.assignDeployMode(mode);
        return s;
    }

    private void ownedAndConnected() {
        when(projectRepository.findByIdAndOwnerUserId(7L, 9L)).thenReturn(Optional.of(mock(Project.class)));
        when(cloudConnectionRepository.findById(5L)).thenReturn(Optional.of(mock(CloudConnection.class)));
        when(ssmRunCommandClient.runShellCommand(any(), eq("i-123"), any())).thenReturn("로그 내용");
    }

    private String capturedCommand() {
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(ssmRunCommandClient).runShellCommand(any(), eq("i-123"), cmd.capture());
        return cmd.getValue();
    }

    @Test
    void nativeApp_tailsLogFile() {
        when(serverRepository.findById(1L)).thenReturn(Optional.of(running(ServerDeployMode.NATIVE)));
        ownedAndConnected();

        var logs = service.fetchLogs(9L, 1L, ServerLogSource.APP);

        assertThat(logs.content()).isEqualTo("로그 내용");
        assertThat(logs.source()).isEqualTo("APP");
        assertThat(capturedCommand()).contains("tail -n 200 /var/log/qeploy-app.log");
    }

    @Test
    void dockerApp_usesComposeOrContainerLogs() {
        when(serverRepository.findById(1L)).thenReturn(Optional.of(running(ServerDeployMode.DOCKER)));
        ownedAndConnected();

        service.fetchLogs(9L, 1L, ServerLogSource.APP);

        String cmd = capturedCommand();
        assertThat(cmd).contains("/opt/app/compose.yml");        // compose 있으면 compose logs
        assertThat(cmd).contains("docker logs --tail 200 qeploy-app");   // 단일 run 폴백(이름 지정)
    }

    @Test
    void bootSource_tailsCloudInit() {
        when(serverRepository.findById(1L)).thenReturn(Optional.of(running(ServerDeployMode.NATIVE)));
        ownedAndConnected();

        service.fetchLogs(9L, 1L, ServerLogSource.BOOT);

        assertThat(capturedCommand()).contains("/var/log/cloud-init-output.log");
    }

    @Test
    void terminatedServer_rejected_noSsmCall() {
        LocalDateTime now = LocalDateTime.now();
        ProvisionedServer terminated = new ProvisionedServer(1L, 7L, "t3.micro", ServerStatus.TERMINATED,
                5L, "i-123", null, 8080, null, null, null, now, now);
        when(serverRepository.findById(1L)).thenReturn(Optional.of(terminated));
        when(projectRepository.findByIdAndOwnerUserId(7L, 9L)).thenReturn(Optional.of(mock(Project.class)));

        assertThatThrownBy(() -> service.fetchLogs(9L, 1L, ServerLogSource.APP))
                .isInstanceOf(IllegalStateException.class);
        verify(ssmRunCommandClient, never()).runShellCommand(any(), any(), any());
    }

    @Test
    void failedServer_withBootDiagnostics_returnsSnapshot_noSsmCall() {
        LocalDateTime now = LocalDateTime.now();
        ProvisionedServer failed = new ProvisionedServer(1L, 7L, "t3.micro", ServerStatus.FAILED,
                5L, "i-123", null, 8080, null, null, null, now, now);
        failed.restoreBootDiagnostics("cloud-init: compose up 실패");
        when(serverRepository.findById(1L)).thenReturn(Optional.of(failed));
        when(projectRepository.findByIdAndOwnerUserId(7L, 9L)).thenReturn(Optional.of(mock(Project.class)));

        // 종료된 서버라도 부트 실패 진단은 보존돼 있으므로 그 스냅샷을 돌려준다.
        var logs = service.fetchLogs(9L, 1L, ServerLogSource.BOOT);

        assertThat(logs.source()).isEqualTo("BOOT");
        assertThat(logs.content()).contains("compose up 실패");
        verify(ssmRunCommandClient, never()).runShellCommand(any(), any(), any());   // 라이브 조회 안 함
    }

    @Test
    void failedServer_bootSourceButNoDiagnostics_rejected() {
        LocalDateTime now = LocalDateTime.now();
        ProvisionedServer failed = new ProvisionedServer(1L, 7L, "t3.micro", ServerStatus.FAILED,
                5L, "i-123", null, 8080, null, null, null, now, now);   // 보존된 진단 없음
        when(serverRepository.findById(1L)).thenReturn(Optional.of(failed));
        when(projectRepository.findByIdAndOwnerUserId(7L, 9L)).thenReturn(Optional.of(mock(Project.class)));

        assertThatThrownBy(() -> service.fetchLogs(9L, 1L, ServerLogSource.BOOT))
                .isInstanceOf(IllegalStateException.class);
        verify(ssmRunCommandClient, never()).runShellCommand(any(), any(), any());
    }

    @Test
    void notOwned_rejected() {
        when(serverRepository.findById(1L)).thenReturn(Optional.of(running(ServerDeployMode.NATIVE)));
        when(projectRepository.findByIdAndOwnerUserId(7L, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fetchLogs(9L, 1L, ServerLogSource.APP))
                .isInstanceOf(NotFoundException.class);
        verify(ssmRunCommandClient, never()).runShellCommand(any(), any(), any());
    }
}
