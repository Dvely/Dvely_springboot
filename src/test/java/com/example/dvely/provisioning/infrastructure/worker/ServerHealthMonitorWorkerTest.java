package com.example.dvely.provisioning.infrastructure.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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

/**
 * 헬스 모니터는 다중 인스턴스 안전을 위해 전체-엔티티 저장 대신 targeted UPDATE 만 쓴다 — 그래서 이 테스트들은
 * 도메인 객체 상태가 아니라 리포지토리 호출(recordHealth·claimRecovery·clearRecoveryAttempt)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ServerHealthMonitorWorkerTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @Mock private TcpHealthChecker healthChecker;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private SsmRunCommandClient ssmRunCommandClient;
    @InjectMocks private ServerHealthMonitorWorker worker;

    /** id=1L, cloudConnectionId=5L, instanceId="i-1", port 8080. NATIVE(기본). */
    private ProvisionedServer running(String host) {
        LocalDateTime now = LocalDateTime.now();
        return new ProvisionedServer(1L, 7L, "t3.micro", ServerStatus.RUNNING,
                5L, "i-1", host, 8080, null, null, null, now, now);
    }

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

        verify(serverRepository).recordHealth(1L, true);
        verify(serverRepository, never()).claimRecovery(anyLong());
    }

    @Test
    void appDown_recordsUnhealthy_withoutTerminating() {
        ProvisionedServer server = running("1.2.3.4");
        server.recordHealthCheck(true);   // 직전엔 건강했다(fetch 시점 DB 값)
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);   // 이제 포트 무응답

        worker.monitorRunningServers();

        verify(serverRepository).recordHealth(1L, false);   // 무응답 기록
        verify(serverRepository, never()).claimRecovery(anyLong());   // 첫 무응답 — 복구 안 함
    }

    @Test
    void noPublicHost_skipped() {
        ProvisionedServer server = running(null);
        batch(server);

        worker.monitorRunningServers();

        verify(healthChecker, never()).isHealthy(any(), anyInt());
        verify(serverRepository, never()).recordHealth(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ── 자동복구(원자적) ─────────────────────────────────────────────────────

    @Test
    void secondConsecutiveUnhealthy_docker_claimsAndRestarts() {
        ProvisionedServer server = runningDocker("1.2.3.4");
        server.recordHealthCheck(false);   // 직전에도 무응답(2회 연속)
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);
        when(cloudConnectionRepository.findById(5L)).thenReturn(Optional.of(mock(CloudConnection.class)));
        when(serverRepository.claimRecovery(1L)).thenReturn(true);   // 이 인스턴스가 복구 권한 획득

        worker.monitorRunningServers();

        verify(serverRepository).claimRecovery(1L);   // 원자적 claim
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(ssmRunCommandClient).runShellCommand(any(), eq("i-1"), cmd.capture());
        org.assertj.core.api.Assertions.assertThat(cmd.getValue()).contains("docker restart qeploy-app");
    }

    @Test
    void secondConsecutiveUnhealthy_claimLost_doesNotRestart() {
        ProvisionedServer server = runningDocker("1.2.3.4");
        server.recordHealthCheck(false);
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);
        when(cloudConnectionRepository.findById(5L)).thenReturn(Optional.of(mock(CloudConnection.class)));
        when(serverRepository.claimRecovery(1L)).thenReturn(false);   // 다른 인스턴스가 이미 복구 claim

        worker.monitorRunningServers();

        verify(ssmRunCommandClient, never()).runShellCommand(any(), any(), any());   // 이중 재시작 방지
    }

    @Test
    void singleUnhealthy_doesNotAttemptRecovery() {
        ProvisionedServer server = runningDocker("1.2.3.4");
        server.recordHealthCheck(true);   // 직전엔 정상 — 이번이 첫 무응답
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);

        worker.monitorRunningServers();

        verify(serverRepository, never()).claimRecovery(anyLong());
        verify(ssmRunCommandClient, never()).runShellCommand(any(), any(), any());
    }

    @Test
    void healthyAgain_clearsRecoveryAttempt() {
        ProvisionedServer server = runningDocker("1.2.3.4");
        server.recordHealthCheck(false);
        server.markRecoveryAttempted();   // 이전에 복구 시도했음(fetch 시점 DB 값)
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(true);   // 회복됨

        worker.monitorRunningServers();

        verify(serverRepository).recordHealth(1L, true);
        verify(serverRepository).clearRecoveryAttempt(1L);   // 다음 장애 대비 초기화
    }

    @Test
    void alreadyAttempted_doesNotRetry() {
        ProvisionedServer server = runningDocker("1.2.3.4");
        server.recordHealthCheck(false);
        server.markRecoveryAttempted();   // 이번 에피소드에 이미 시도함
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);

        worker.monitorRunningServers();

        verify(serverRepository, never()).claimRecovery(anyLong());   // 재claim·재시작 안 함
        verify(ssmRunCommandClient, never()).runShellCommand(any(), any(), any());
    }

    @Test
    void nativeServer_secondConsecutiveUnhealthy_claimsAndRestartsWithNativeCommand() {
        ProvisionedServer server = running("1.2.3.4");   // NATIVE(java -jar/npm start)
        server.recordHealthCheck(false);   // 2회 연속 무응답
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);
        when(cloudConnectionRepository.findById(5L)).thenReturn(Optional.of(mock(CloudConnection.class)));
        when(serverRepository.claimRecovery(1L)).thenReturn(true);

        worker.monitorRunningServers();

        verify(serverRepository).claimRecovery(1L);
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(ssmRunCommandClient).runShellCommand(any(), eq("i-1"), cmd.capture());
        // NATIVE 재시작: 포트 쥔 프로세스 kill → SSM env 재export → jar/node 재기동.
        org.assertj.core.api.Assertions.assertThat(cmd.getValue()).contains("pkill");
        org.assertj.core.api.Assertions.assertThat(cmd.getValue()).contains("java -jar /opt/app/app.jar --server.port=8080");
        org.assertj.core.api.Assertions.assertThat(cmd.getValue()).contains("npm start");
        org.assertj.core.api.Assertions.assertThat(cmd.getValue()).doesNotContain("docker");   // DOCKER 명령 아님
    }

    @Test
    void restartSsmFails_claimStillTaken() {
        ProvisionedServer server = runningDocker("1.2.3.4");
        server.recordHealthCheck(false);
        batch(server);
        when(healthChecker.isHealthy("1.2.3.4", 8080)).thenReturn(false);
        when(cloudConnectionRepository.findById(5L)).thenReturn(Optional.of(mock(CloudConnection.class)));
        when(serverRepository.claimRecovery(1L)).thenReturn(true);
        when(ssmRunCommandClient.runShellCommand(any(), eq("i-1"), any()))
                .thenThrow(new RuntimeException("SSM 실패"));

        worker.monitorRunningServers();

        // claim 은 재시작 시도 전에 이뤄지므로, SSM 이 실패해도 이번 에피소드는 1회로 카운트된다(폭주 방지).
        verify(serverRepository).claimRecovery(1L);
    }
}
