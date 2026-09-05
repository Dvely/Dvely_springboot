package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.TcpHealthChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RUNNING 서버의 앱 건강을 주기적으로 확인한다. 상태 워커({@code ProvisionedServerStatusWorker})는
 * PROVISIONING→RUNNING 전이까지만 보므로, RUNNING 이 된 뒤 앱이 죽어도(포트 무응답) 서버는 계속 RUNNING
 * 으로 보이는 공백이 있었다 — 이 워커가 RUNNING 서버를 TCP 헬스체크해 {@code healthy}/{@code lastHealthCheckAt}
 * 를 갱신한다. <b>인스턴스는 종료하지 않는다</b>: 앱만 죽은 것이라 인스턴스는 살려 두고(로그 조회·재배포 가능)
 * "앱 무응답"만 드러낸다.
 *
 * <p>순수 TCP 접속만 하고(사용자 계정 자격 불필요) DB 에 결과를 기록한다. 원자적 claim 없음 — 헬스체크는
 * 읽기, recordHealthCheck 는 최신값 덮어쓰기라 겹친 폴링이 해가 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerHealthMonitorWorker {

    private static final int BATCH = 50;

    private final ProvisionedServerRepository serverRepository;
    private final TcpHealthChecker healthChecker;

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
                serverRepository.save(server);
                if (Boolean.TRUE.equals(previous) && !healthy) {
                    log.warn("앱 헬스 이상(RUNNING 이지만 포트 무응답 — 앱이 죽었을 수 있음): serverId={} host={}:{} projectId={}",
                            server.getId(), server.getPublicHost(), server.getPort(), server.getProjectId());
                } else if (Boolean.FALSE.equals(previous) && healthy) {
                    log.info("앱 헬스 회복: serverId={} host={}:{}",
                            server.getId(), server.getPublicHost(), server.getPort());
                }
            } catch (RuntimeException e) {
                // 이 서버만 건너뛰고 다음 주기에 다시 본다.
                log.warn("서버 헬스 모니터 실패(다음 주기 재시도): serverId={} 원인={}", server.getId(), e.toString());
            }
        }
    }
}
