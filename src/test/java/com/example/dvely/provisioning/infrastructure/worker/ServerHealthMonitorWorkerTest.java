package com.example.dvely.provisioning.infrastructure.worker;

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
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.SsmRunCommandClient;
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
class ServerHealthMonitorWorkerTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @Mock private TcpHealthChecker healthChecker;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private SsmRunCommandClient ssmRunCommandClient;
    @InjectMocks private ServerHealthMonitorWorker worker;

    /** NATIVE 서버(기본). */
    private ProvisionedServer running(String host) {
        LocalDateTime now = LocalDateTime.now();
        return new ProvisionedServer(1L, 7L, "t3.micro", ServerStatus.RUNNING,
                5L, "i-1", host, 8080, null, null, null, now, now);
    }

    /** DOCKER 서버(자동복구 대상). cloudConnectionId=5L, instanceId="i-1". */
    private ProvisionedServer runningDocker(String host) {
        ProvisionedServer s = running(host);
        s.assignDeployMode(ServerDeployMode.DOCKER);
        return s;
    }

    private void batch(ProvisionedServer server) {
        when(serverRepository.findByStatus(eq(ServerStatus.RUNNING), anyInt())).thenReturn(List.of(server));
    }

    @Test
    void healthyServer_recordsHealthyTrue() {
        ProvisionedServer server = running("1.2.3.4");
        batch(server);
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
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);   // 이제 포트 무응답

        worker.monitorRunningServers();

        assertThat(server.getHealthy()).isFalse();          // 앱 무응답으로 표시
        assertThat(server.getStatus()).isEqualTo(ServerStatus.RUNNING);   // 인스턴스는 종료 안 함
        verify(serverRepository).save(server);
    }

    @Test
    void noPublicHost_skipped() {
        ProvisionedServer server = running(null);
        batch(server);

        worker.monitorRunningServers();

        verify(healthChecker, never()).isHealthy(any(), anyInt());
        verify(serverRepository, never()).save(any());
    }

    // ── 자동복구 ─────────────────────────────────────────────────────────────

    @Test
    void secondConsecutiveUnhealthy_docker_attemptsRestart() {
        ProvisionedServer server = runningDocker("1.2.3.4");
        server.recordHealthCheck(false);   // 직전에도 무응답이었다(2회 연속)
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);   // 여전히 무응답
        when(cloudConnectionRepository.findById(5L)).thenReturn(Optional.of(mock(CloudConnection.class)));
        when(ssmRunCommandClient.runShellCommand(any(), eq("i-1"), any())).thenReturn("restarted");

        worker.monitorRunningServers();

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(ssmRunCommandClient).runShellCommand(any(), eq("i-1"), cmd.capture());
        assertThat(cmd.getValue()).contains("docker restart qeploy-app");   // 단일 컨테이너 재시작
        assertThat(cmd.getValue()).contains("/opt/app/compose.yml");        // compose 분기도 포함
        assertThat(server.getRecoveryAttemptedAt()).isNotNull();            // 시도 표시
        assertThat(server.getStatus()).isEqualTo(ServerStatus.RUNNING);     // 인스턴스는 종료 안 함
        verify(serverRepository).save(server);
    }

    @Test
    void singleUnhealthy_doesNotAttemptRestart() {
        ProvisionedServer server = runningDocker("1.2.3.4");
        server.recordHealthCheck(true);   // 직전엔 정상 — 이번이 첫 무응답
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);

        worker.monitorRunningServers();

        verify(ssmRunCommandClient, never()).runShellCommand(any(), any(), any());   // 흔들림에 재시작 안 함
        assertThat(server.getRecoveryAttemptedAt()).isNull();
        verify(serverRepository).save(server);
    }

    @Test
    void healthyAgain_clearsRecoveryAttempt() {
        ProvisionedServer server = runningDocker("1.2.3.4");
        server.recordHealthCheck(false);
        server.markRecoveryAttempted();   // 이전에 복구를 시도했음
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(true);   // 회복됨

        worker.monitorRunningServers();

        assertThat(server.getHealthy()).isTrue();
        assertThat(server.getRecoveryAttemptedAt()).isNull();   // 다음 장애 대비 초기화
        verify(ssmRunCommandClient, never()).runShellCommand(any(), any(), any());
        verify(serverRepository).save(server);
    }

    @Test
    void alreadyAttempted_doesNotRetry() {
        ProvisionedServer server = runningDocker("1.2.3.4");
        server.recordHealthCheck(false);
        server.markRecoveryAttempted();   // 이번 에피소드에 이미 시도함
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);   // 여전히 무응답

        worker.monitorRunningServers();

        verify(ssmRunCommandClient, never()).runShellCommand(any(), any(), any());   // 재시작 루프 방지
        verify(serverRepository).save(server);
    }

    @Test
    void nativeServer_secondUnhealthy_noRestart() {
        ProvisionedServer server = running("1.2.3.4");   // NATIVE — 자동복구 미지원
        server.recordHealthCheck(false);
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);

        worker.monitorRunningServers();

        verify(ssmRunCommandClient, never()).runShellCommand(any(), any(), any());
        assertThat(server.getRecoveryAttemptedAt()).isNull();   // 시도 표시도 안 남긴다
        verify(serverRepository).save(server);
    }

    @Test
    void restartSsmFails_stillMarksAttempted() {
        ProvisionedServer server = runningDocker("1.2.3.4");
        server.recordHealthCheck(false);
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);
        when(cloudConnectionRepository.findById(5L)).thenReturn(Optional.of(mock(CloudConnection.class)));
        when(ssmRunCommandClient.runShellCommand(any(), eq("i-1"), any()))
                .thenThrow(new RuntimeException("SSM 실패"));

        worker.monitorRunningServers();

        // 실패해도 1회 시도로 카운트해 이번 에피소드엔 다시 안 두드린다(폭주 방지). 인스턴스는 그대로.
        assertThat(server.getRecoveryAttemptedAt()).isNotNull();
        assertThat(server.getStatus()).isEqualTo(ServerStatus.RUNNING);
        verify(serverRepository).save(server);
    }
}
