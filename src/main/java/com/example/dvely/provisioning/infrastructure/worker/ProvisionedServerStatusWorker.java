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
            // 다중 인스턴스: 부트 타임아웃 처리 권한을 CAS 로 claim(PROVISIONING→FAILED). 진 인스턴스
            // 하나만 진행한다 — 두 곳이 같은 인스턴스를 종료하거나 SSM 으로 로그를 뜨지 않게.
            if (!serverRepository.claimBootTimeout(server.getId())) {
                return;
            }
            // terminate 하기 전에 부트 로그를 떠 둔다 — 인스턴스가 사라지면 왜 안 떴는지 볼 길이 없어진다.
            captureBootDiagnostics(connection.get(), server);
            safeTerminateAndReleaseEip(connection.get(), server);
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

    /**
     * 인스턴스를 끄고 <b>EIP 도 함께 놓는다</b>(#344 9-4).
     *
     * <p>EIP 는 {@code beginProvisioning} <b>전에</b> 할당·연결된다({@code BackendDeployRunner}) — 즉
     * 부트 타임아웃 시점에는 반드시 존재한다. 그리고 인스턴스가 종료돼도 <b>연결만 풀리고 할당은 남아
     * 계속 과금된다.</b> 이 경로에 release 가 없어서, 부트 타임아웃마다 유휴 EIP 가 남았다.</p>
     *
     * <p>{@code OrphanElasticIpSweeper} 가 받쳐 주지만 그것에 의존하면 안 된다. 그 스윕은 같은 연결에
     * 인플라이트 배포(QUEUED/BUILDING/PROVISIONING)가 하나라도 있으면 <b>그 연결을 통째로 건너뛴다</b>
     * (아직 연결 전인 EIP 를 오회수하지 않으려는 안전장치다). 그래서 노출이 스윕 주기보다 길어질 수
     * 있다 — 배포가 잦은 연결일수록 길어진다. 여기서 즉시 놓으면 스윕은 본래 역할인 안전망으로만 남는다.</p>
     *
     * <p>둘 다 best-effort 다. terminate 실패는 아직 켜져 있는 인스턴스라 {@code error} 로, EIP release
     * 실패는 유휴 과금이라 {@code warn} 으로 남긴다 — 정상 종료 경로
     * ({@code ServerProvisioningCommandService})와 같은 등급·문구를 쓴다.</p>
     */
    private void safeTerminateAndReleaseEip(CloudConnection connection, ProvisionedServer server) {
        try {
            ec2.terminate(connection, server.getInstanceId());
        } catch (RuntimeException e) {
            log.error("타임아웃 terminate 실패(수동 정리 필요): instanceId={} 원인={}",
                    server.getInstanceId(), e.toString());
        }
        if (server.getElasticIpAllocationId() == null) {
            return;
        }
        try {
            ec2.releaseElasticIp(connection, server.getElasticIpAllocationId());
        } catch (RuntimeException e) {
            log.warn("타임아웃 후 EIP release 실패(수동 정리 필요, 유휴 EIP 과금 주의): allocationId={} 원인={}",
                    server.getElasticIpAllocationId(), e.getMessage());
        }
    }
}
