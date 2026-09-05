package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.SsmRunCommandClient;
import com.example.dvely.provisioning.infrastructure.TcpHealthChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RUNNING 서버의 앱 건강을 주기적으로 확인하고, 무응답이 이어지면 <b>재시작으로 자동복구</b>를 시도한다.
 * 상태 워커({@code ProvisionedServerStatusWorker})는 PROVISIONING→RUNNING 전이까지만 보므로, RUNNING 이
 * 된 뒤 앱이 죽어도(포트 무응답) 서버는 계속 RUNNING 으로 보이는 공백이 있었다 — 이 워커가 그걸 메운다.
 *
 * <p><b>복구 정책</b>: 순간적인 헬스 흔들림에 재시작을 남발하지 않으려 <b>2회 연속 무응답</b>일 때만
 * 시도한다(직전 주기도 false 였을 때). 한 장애 에피소드당 <b>1회만</b> 시도하고({@code recoveryAttemptedAt}
 * 표시), 회복되면 표시를 지워 다음 장애에 다시 시도한다. 재시작이 소용없으면(에피소드 내 재시도 안 함)
 * 무응답인 채로 두고 사용자가 로그를 보거나 재배포하도록 남긴다 — 재시작 루프를 만들지 않는다.
 *
 * <p><b>DOCKER 모드만</b> 복구한다. NATIVE({@code nohup java -jar}/{@code npm start})는 관리 프로세스가
 * 아니라 안전한 자동 재시작이 어렵다(원 실행 명령·런타임을 재구성해야 함) — 감지만 하고 재배포에 맡긴다.
 *
 * <p>인스턴스는 종료하지 않는다: 앱만 죽은 것이라 인스턴스는 살려 두고 재시작·로그 조회를 한다. 원자적
 * claim 없음 — 헬스체크는 읽기, 기록은 최신값 덮어쓰기, 복구는 표시로 1회 보장이라 겹친 폴링이 해가 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerHealthMonitorWorker {

    private static final int BATCH = 50;

    private final ProvisionedServerRepository serverRepository;
    private final TcpHealthChecker healthChecker;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final SsmRunCommandClient ssmRunCommandClient;

    // 자동복구 킬스위치. 문제가 생기면 재시작 시도만 끄고 감지·기록은 유지할 수 있다. 테스트는 필드
    // 초기값(true)을 쓰고(스프링 없이도 켜짐), 운영은 @Value 가 설정값으로 덮어쓴다.
    @Value("${qeploy.provisioning.auto-recovery-enabled:true}")
    private boolean autoRecoveryEnabled = true;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.health-monitor-interval-ms:60000}")
    public void monitorRunningServers() {
        for (ProvisionedServer server : serverRepository.findByStatus(ServerStatus.RUNNING, BATCH)) {
            if (server.getPublicHost() == null || server.getPublicHost().isBlank()) {
                continue;   // 주소가 없으면 확인할 수 없다(정상 RUNNING 이면 항상 있음)
            }
            try {
                boolean healthy = healthChecker.isHealthy(server.getPublicHost(), server.getPort());
                Boolean previous = server.getHealthy();
                server.recordHealthCheck(healthy);

                if (healthy) {
                    if (server.hasRecoveryBeenAttempted()) {
                        server.clearRecoveryAttempt();   // 회복 — 다음 장애에 다시 복구할 수 있게 초기화
                    }
                    if (Boolean.FALSE.equals(previous)) {
                        log.info("앱 헬스 회복: serverId={} host={}:{}",
                                server.getId(), server.getPublicHost(), server.getPort());
                    }
                } else {
                    if (Boolean.TRUE.equals(previous)) {
                        log.warn("앱 헬스 이상(RUNNING 이지만 포트 무응답 — 앱이 죽었을 수 있음): serverId={} host={}:{} projectId={}",
                                server.getId(), server.getPublicHost(), server.getPort(), server.getProjectId());
                    }
                    // 2회 연속 무응답 + 이번 에피소드에 아직 복구 안 함 → 재시작으로 자동복구 시도.
                    if (Boolean.FALSE.equals(previous) && !server.hasRecoveryBeenAttempted()) {
                        attemptRecovery(server);
                    }
                }
                serverRepository.save(server);
            } catch (RuntimeException e) {
                // 이 서버만 건너뛰고 다음 주기에 다시 본다.
                log.warn("서버 헬스 모니터 실패(다음 주기 재시도): serverId={} 원인={}", server.getId(), e.toString());
            }
        }
    }

    /**
     * 무응답 앱을 SSM 으로 재시작한다. 먼저 시도 표시를 남겨(성공·실패 무관) 이번 에피소드엔 다시 시도하지
     * 않는다 — 재시작 루프 방지. 표시 뒤 저장은 호출부의 save 가 healthy 와 함께 처리한다.
     */
    private void attemptRecovery(ProvisionedServer server) {
        if (!autoRecoveryEnabled) {
            return;
        }
        if (server.getDeployMode() != ServerDeployMode.DOCKER) {
            log.warn("자동복구 미지원(NATIVE 서버는 재배포 필요): serverId={}", server.getId());
            return;
        }
        CloudConnection connection = server.getCloudConnectionId() == null ? null
                : cloudConnectionRepository.findById(server.getCloudConnectionId()).orElse(null);
        if (connection == null) {
            log.warn("자동복구 건너뜀(클라우드 연결 없음): serverId={}", server.getId());
            return;
        }
        server.markRecoveryAttempted();
        try {
            String output = ssmRunCommandClient.runShellCommand(
                    connection, server.getInstanceId(), buildRestartCommand());
            log.warn("앱 무응답 자동복구 시도(재시작): serverId={} instanceId={} 출력={}",
                    server.getId(), server.getInstanceId(),
                    output == null ? "" : output.substring(0, Math.min(200, output.length())));
        } catch (RuntimeException e) {
            log.warn("자동복구 재시작 실패(재배포 필요할 수 있음): serverId={} 원인={}",
                    server.getId(), e.toString());
        }
    }

    /**
     * DOCKER 앱 재시작 명령. compose.yml 존재로 compose↔단일 run 을 가른다(로그 명령과 같은 분기).
     * 컨테이너가 hang 이든(응답만 멈춤) restart 정책이 소진돼 죽어 있든 restart 가 되살린다.
     */
    private String buildRestartCommand() {
        return "if [ -f /opt/app/compose.yml ]; then "
                + "docker compose -f /opt/app/compose.yml --project-directory /opt/app restart 2>&1; "
                + "else docker restart qeploy-app 2>&1; fi";
    }
}
