package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner.Ec2InstanceStatus;
import com.example.dvely.provisioning.infrastructure.SsmRunCommandClient;
import com.example.dvely.provisioning.infrastructure.TcpHealthChecker;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * launch 후 PROVISIONING 상태인 EC2 서버를 폴링해 마무리한다. describe 로 running 을 확인하고,
 * 앱 포트가 열리면(TCP 헬스체크) RUNNING 으로 넘겨 publicHost 를 채운다. 인스턴스가 종료·정지 상태면
 * FAILED. 너무 오래 못 뜨면(앱이 안 기동) 과금을 멈추려 terminate 하고 FAILED 로 닫는다.
 *
 * <p>생성에 쓴 cloudConnectionId 로 조회한다(프로젝트 '현재' 선택 아님) — RDS 상태 워커와 같은 이유.
 * '아직 대기'인 주기에는 저장하지 않으므로, updatedAt 은 beginProvisioning 시각에 머문다 — 그걸
 * 기동 타임아웃의 기준으로 쓴다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProvisionedServerStatusWorker {

    private static final int BATCH = 20;
    private static final Duration BOOT_TIMEOUT = Duration.ofMinutes(20);
    private static final Set<String> TERMINAL_STATES =
            Set.of("terminated", "stopping", "stopped", "shutting-down");

    private final ProvisionedServerRepository serverRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final Ec2Provisioner ec2;
    private final TcpHealthChecker healthChecker;
    private final SsmRunCommandClient ssmRunCommandClient;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.server-poll-interval-ms:20000}")
    public void pollProvisioning() {
        for (ProvisionedServer server : serverRepository.findByStatus(ServerStatus.PROVISIONING, BATCH)) {
            try {
                pollOne(server);
            } catch (RuntimeException e) {
                log.warn("EC2 서버 상태 폴링 실패(다음 주기 재시도): serverId={} instanceId={} 원인={}",
                        server.getId(), server.getInstanceId(), e.toString());
            }
        }
    }

    private void pollOne(ProvisionedServer server) {
        Optional<CloudConnection> connection = server.getCloudConnectionId() == null
                ? Optional.empty()
                : cloudConnectionRepository.findById(server.getCloudConnectionId());
        if (connection.isEmpty()) {
            log.warn("EC2 서버 상태 폴링 건너뜀(클라우드 연결 없음): serverId={}", server.getId());
            return;
        }
        Ec2InstanceStatus status = ec2.describe(connection.get(), server.getInstanceId());

        if (TERMINAL_STATES.contains(status.state())) {
            server.markFailed(ProvisionFailureCode.PROVIDER_ERROR,
                    "인스턴스가 " + status.state() + " 상태입니다.");
            serverRepository.save(server);
            log.warn("EC2 서버 실패(인스턴스 종료됨): serverId={} state={}", server.getId(), status.state());
            return;
        }

        if ("running".equals(status.state()) && status.publicHost() != null
                && healthChecker.isHealthy(status.publicHost(), server.getPort())) {
            server.markRunning(status.publicHost());
            serverRepository.save(server);
            log.info("EC2 서버 RUNNING: serverId={} host={}:{} projectId={}",
                    server.getId(), status.publicHost(), server.getPort(), server.getProjectId());
            return;
        }

        // 아직 기동 중 — 너무 오래면(앱이 안 뜸) 과금을 멈추고 실패로 닫는다.
        if (server.getUpdatedAt().plus(BOOT_TIMEOUT).isBefore(LocalDateTime.now())) {
            // terminate 하기 전에 부트 로그를 떠 둔다 — 인스턴스가 사라지면 왜 안 떴는지 볼 길이 없어진다.
            captureBootDiagnostics(connection.get(), server);
            safeTerminate(connection.get(), server.getInstanceId());
            server.markFailed(ProvisionFailureCode.PROVIDER_ERROR,
                    "제한 시간 안에 앱이 기동하지 않았습니다(포트 " + server.getPort() + " 응답 없음).");
            serverRepository.save(server);
            log.warn("EC2 서버 기동 타임아웃 → terminate: serverId={} instanceId={}",
                    server.getId(), server.getInstanceId());
        }
        // 그 외: 다음 주기에 다시 본다(저장 안 함 — updatedAt 유지).
    }

    /**
     * terminate 직전에 부트 로그(cloud-init)를 한 번 떠서 서버에 보존한다. 베스트 에포트 — SSM 미등록·
     * 타임아웃으로 실패해도 terminate 는 그대로 진행한다(진단 없는 실패가 더 나쁠 것은 없다). 저장은
     * 호출부의 markFailed 뒤 save 가 함께 처리한다.
     */
    private void captureBootDiagnostics(CloudConnection connection, ProvisionedServer server) {
        try {
            String bootLog = ssmRunCommandClient.runShellCommand(
                    connection, server.getInstanceId(), SsmRunCommandClient.BOOT_LOG_TAIL);
            server.recordBootDiagnostics(bootLog);
            log.info("부트 타임아웃 진단 보존: serverId={} 길이={}",
                    server.getId(), bootLog == null ? 0 : bootLog.length());
        } catch (RuntimeException e) {
            log.warn("부트 진단 보존 실패(그대로 terminate 진행): serverId={} 원인={}",
                    server.getId(), e.toString());
        }
    }

    private void safeTerminate(CloudConnection connection, String instanceId) {
        try {
            ec2.terminate(connection, instanceId);
        } catch (RuntimeException e) {
            log.error("타임아웃 terminate 실패(수동 정리 필요): instanceId={} 원인={}", instanceId, e.toString());
        }
    }
}
