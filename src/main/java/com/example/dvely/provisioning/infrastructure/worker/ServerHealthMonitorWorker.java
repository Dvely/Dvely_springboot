package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.HttpHealthProbe;
import com.example.dvely.provisioning.infrastructure.SsmRunCommandClient;
import com.example.dvely.provisioning.infrastructure.TcpHealthChecker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * 무응답인 채로 두되, 그 사실을 감사 이벤트로 1회 보고하고(아래) 사용자가 로그를 보거나 재배포하도록 남긴다
 * — 재시작 루프를 만들지 않는다.
 *
 * <p>DOCKER(compose/docker restart)·NATIVE(포트 프로세스 kill→SSM env 재export→java -jar/npm start) 둘 다
 * 재시작한다. 자동복구를 시도하면 감사 이벤트({@code SERVER_RECOVERY_ATTEMPTED})를 남겨 사용자가 감사 로그에서
 * 앱 불안정(잦은 자동재시작)이나 재시작 명령 실패를 볼 수 있다. 다만 {@code SUCCEEDED}는 재시작 <i>명령이
 * 실행됐다</i>는 뜻일 뿐 앱이 살아났다는 보장이 아니다 — 재시작 후 정착 유예({@code recovery-settle-ms},
 * 기본 2분)가 지나도록 여전히 무응답이면 자가치유 실패로 {@code SERVER_RECOVERY_FAILED}(에피소드당 1회, 원자
 * claim)를 남겨 개입(로그·재배포)이 필요함을 알린다.
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
    private final HttpHealthProbe httpHealthProbe;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final SsmRunCommandClient ssmRunCommandClient;
    private final AuditRecorder auditRecorder;

    // 자동복구 킬스위치. 문제가 생기면 재시작 시도만 끄고 감지·기록은 유지할 수 있다. 테스트는 필드
    // 초기값(true)을 쓰고(스프링 없이도 켜짐), 운영은 @Value 가 설정값으로 덮어쓴다.
    @Value("${qeploy.provisioning.auto-recovery-enabled:true}")
    private boolean autoRecoveryEnabled = true;

    // 자동 재시작 후 "복구 실패" 로 판정하기 전 앱이 다시 뜰 시간을 주는 정착 유예. 재시작 명령 실행 시각
    // (recovery_attempted_at)이 이 유예를 지나도록 여전히 무응답이면 그때 실패로 보고한다 — 조기 오탐 방지.
    @Value("${qeploy.provisioning.recovery-settle-ms:120000}")
    private long recoverySettleMs = 120000;

    /**
     * 프로브는 <b>병렬</b>로, 판정·기록은 <b>직렬</b>로 한다(#344 9-5).
     *
     * <p>전에는 배치 전체를 직렬로 돌았다. 서버당 TCP 2초 + HTTP(연결 2초 + 요청 3초)가 모두 타임아웃에
     * 걸리면 50대 x 5초 = <b>최악 250초</b> 동안 스케줄러 스레드 하나를 붙들었다. 주기가 60초이므로 그
     * 사이 자기 다음 실행이 밀리고, 같은 풀을 쓰는 다른 워커도 굶는다. 그리고 이 워커가 느려지는 조건이
     * 바로 <b>서버들이 실제로 죽어 있을 때</b>다 — 가장 빨라야 할 때 가장 느렸다.</p>
     *
     * <p>프로브는 DB 를 만지지 않는 순수 I/O 라 병렬화가 안전하다. 반면 판정·기록은 직렬로 남긴다 —
     * 복구 경로가 SSM 호출과 원자 claim 을 하고, 동시 실행으로 얻을 것이 없다(복구는 2회 연속 무응답 +
     * 에피소드당 1회라 드물다).</p>
     *
     * <p>가상 스레드를 쓴다. 프로브는 전부 블로킹 I/O 대기라 플랫폼 스레드를 점유할 이유가 없고,
     * {@code close()} 가 모든 작업의 완료를 기다려 주기 경계가 흐려지지 않는다. 한 배치가 최대
     * {@code BATCH}(50)개이므로 동시성이 그 이상으로 커지지 않는다.</p>
     */
    @Scheduled(fixedDelayString = "${qeploy.provisioning.health-monitor-interval-ms:60000}")
    public void monitorRunningServers() {
        List<ProvisionedServer> servers = serverRepository.findByStatus(ServerStatus.RUNNING, BATCH).stream()
                // 주소가 없으면 확인할 수 없다(정상 RUNNING 이면 항상 있음)
                .filter(server -> server.getPublicHost() != null && !server.getPublicHost().isBlank())
                .toList();
        if (servers.isEmpty()) {
            return;
        }
        Map<Long, Boolean> probed = probeInParallel(servers);
        for (ProvisionedServer server : servers) {
            Boolean healthy = probed.get(server.getId());
            if (healthy == null) {
                continue;   // 프로브가 실패했다 — 기록을 건너뛰어 직전 판정을 흔들지 않는다(다음 주기 재시도)
            }
            try {
                applyHealth(server, healthy);
            } catch (RuntimeException e) {
                // 이 서버만 건너뛰고 다음 주기에 다시 본다.
                log.warn("서버 헬스 모니터 실패(다음 주기 재시도): serverId={} 원인={}", server.getId(), e.toString());
            }
        }
    }

    private Map<Long, Boolean> probeInParallel(List<ProvisionedServer> servers) {
        Map<Long, Boolean> results = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ProvisionedServer server : servers) {
                pool.execute(() -> {
                    try {
                        results.put(server.getId(), probe(server));
                    } catch (RuntimeException e) {
                        // 한 대의 실패가 배치를 멈추지 않게 한다 — 다른 스윕들과 같은 격리 원칙이다.
                        log.warn("서버 헬스 프로브 실패(다음 주기 재시도): serverId={} 원인={}",
                                server.getId(), e.toString());
                    }
                });
            }
        }   // close() 가 모든 프로브의 완료를 기다린다
        return results;
    }

    /**
     * 포트가 열렸는지(TCP=프로세스 살아있음) + 앱이 기능적 이상을 명시적으로 보고하지 않는지
     * (HTTP /api/health 5xx). TCP 만으로는 앱이 뜬 채 DB 등에 못 붙는 경우를 못 잡았다(#3).
     * 5xx 만 이상으로 보므로, 헬스 엔드포인트 없는 앱은 기존 TCP 판정 그대로다.
     */
    private boolean probe(ProvisionedServer server) {
        return healthChecker.isHealthy(server.getPublicHost(), server.getPort())
                && !httpHealthProbe.reportsUnhealthy(server.getPublicHost(), server.getPort());
    }

    private void applyHealth(ProvisionedServer server, boolean healthy) {
                Boolean previous = server.getHealthy();   // fetch 시점 DB 값(디바운스·복구 판정용)
                // 헬스는 targeted UPDATE 로만 쓴다 — 전체-엔티티 저장을 하지 않아, 다중 인스턴스에서 각자
                // 헬스체크·기록해도 lost-update 가 없고 교체 워커의 저장과 충돌하지 않는다.
                serverRepository.recordHealth(server.getId(), healthy);

                if (healthy) {
                    if (server.hasRecoveryBeenAttempted()) {
                        serverRepository.clearRecoveryAttempt(server.getId());   // 회복 — 다음 장애 대비 초기화
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
                    if (server.hasRecoveryBeenAttempted()) {
                        // 이미 이번 에피소드에 재시작을 시도했는데도 여전히 무응답 — 재시작이 소용없었는지 본다.
                        reportRecoveryFailedIfDue(server);
                    } else {
                        attemptRecoveryIfDue(server, previous);
                    }
                }
    }

    /**
     * 2회 연속 무응답(직전도 false) + 이번 에피소드 미시도일 때, 무응답 앱을 SSM 으로 재시작한다. 재시작 직전에
     * {@code claimRecovery} 로 복구 권한을 원자적으로 잡는다 — 다중 인스턴스에서 진 하나만 재시작하고, 이
     * claim 이 recovery_attempted_at 을 남긴다(전체-엔티티 저장을 안 하므로 clobber 되지 않는다). 연결 없음 등
     * 재시작 불가 조건은 claim <b>전에</b> 걸러 에피소드의 1회 기회를 헛되이 소비하지 않는다.
     */
    private void attemptRecoveryIfDue(ProvisionedServer server, Boolean previous) {
        if (!Boolean.FALSE.equals(previous) || server.hasRecoveryBeenAttempted()) {
            return;   // 첫 무응답이거나 이미 이번 에피소드에 시도함 — 대상 아님
        }
        if (!autoRecoveryEnabled) {
            return;
        }
        CloudConnection connection = server.getCloudConnectionId() == null ? null
                : cloudConnectionRepository.findById(server.getCloudConnectionId()).orElse(null);
        if (connection == null) {
            log.warn("자동복구 건너뜀(클라우드 연결 없음): serverId={}", server.getId());
            return;
        }
        if (!serverRepository.claimRecovery(server.getId())) {
            return;   // 다른 인스턴스가 이미 복구를 claim 함 — 이중 재시작 방지
        }
        AuditOutcome outcome;
        String errorSummary = null;
        try {
            String output = ssmRunCommandClient.runShellCommand(
                    connection, server.getInstanceId(), buildRestartCommand(server));
            outcome = AuditOutcome.SUCCEEDED;   // 재시작 명령이 인스턴스에서 실행됨(회복 여부는 다음 주기 헬스로 판정)
            log.warn("앱 무응답 자동복구 시도(재시작, {}): serverId={} instanceId={} 출력={}",
                    server.getDeployMode(), server.getId(), server.getInstanceId(),
                    output == null ? "" : output.substring(0, Math.min(200, output.length())));
        } catch (RuntimeException e) {
            outcome = AuditOutcome.FAILED;   // 재시작 명령 자체가 실패(SSM 미도달 등) — 사용자 개입 필요 신호
            errorSummary = e.toString();
            log.warn("자동복구 재시작 실패(재배포 필요할 수 있음): serverId={} 원인={}",
                    server.getId(), e.toString());
        }
        // 자동복구 시도를 감사 로그에 남긴다(에피소드당 1회, claim 뒤라 중복 없음). AuditRecorder 는 절대
        // 예외를 던지지 않으므로 워커에 안전하다. 사용자는 잦은 이벤트로 앱 불안정을, FAILED 로 재시작 실패를 안다.
        auditRecorder.record(new AuditEvent(
                AuditAction.SERVER_RECOVERY_ATTEMPTED, outcome, AuditActorType.SYSTEM, null,
                server.getProjectId(), "SERVER", String.valueOf(server.getId()), null, null,
                "앱 무응답으로 자동 재시작 시도(" + server.getDeployMode() + ")", errorSummary));
    }

    /**
     * 이미 이번 에피소드에 자동 재시작을 시도했는데(recovery_attempted_at 존재) 정착 유예가 지나도록 여전히
     * 무응답이면, <b>자가치유 실패</b>를 감사 이벤트({@code SERVER_RECOVERY_FAILED})로 <b>에피소드당 1회</b>
     * 남긴다. 재시작 명령은 실행됐어도(그건 {@code SERVER_RECOVERY_ATTEMPTED}/SUCCEEDED) 앱이 되살아나지
     * 못했다는 뜻이라, 사용자에게 개입(로그 확인·재배포) 신호를 준다. claim 이 원자적으로 1회를 보장하고
     * (다중 인스턴스에서 하나만), 정착 유예는 재시작 앱이 뜰 시간을 줘 조기 오탐을 막는다. 앱이 회복되면
     * {@code clearRecoveryAttempt} 가 표시를 지워 다음 에피소드에 다시 시도·보고할 수 있다.
     */
    private void reportRecoveryFailedIfDue(ProvisionedServer server) {
        if (!serverRepository.claimRecoveryOutcomeReport(server.getId(), Duration.ofMillis(recoverySettleMs))) {
            return;   // 아직 정착 유예 안 지남 · 이미 보고함 · 다른 인스턴스가 보고함 · 방금 회복함
        }
        log.warn("자동복구 실패(재시작 후에도 앱 무응답 — 재배포/로그 확인 필요): serverId={} instanceId={} projectId={}",
                server.getId(), server.getInstanceId(), server.getProjectId());
        auditRecorder.record(new AuditEvent(
                AuditAction.SERVER_RECOVERY_FAILED, AuditOutcome.FAILED, AuditActorType.SYSTEM, null,
                server.getProjectId(), "SERVER", String.valueOf(server.getId()), null, null,
                "자동 재시작 후에도 앱이 계속 무응답 — 사용자 개입 필요(로그 확인·재배포)", null));
    }

    /** 실행 형태별 앱 재시작 명령(SSM 으로 실행). */
    private String buildRestartCommand(ProvisionedServer server) {
        return server.getDeployMode() == ServerDeployMode.NATIVE
                ? buildNativeRestartCommand(server)
                : buildDockerRestartCommand();
    }

    /**
     * NATIVE(nohup java -jar / npm start) 앱 재시작. 관리 프로세스가 아니라 systemctl 이 없으므로: ①포트를
     * 쥔 기존 프로세스를 죽이고(hang 대비 — 죽어 있으면 no-op) ②SSM 에서 env 를 다시 export 한 뒤(재시작
     * 앱도 DB 등 env 가 필요하다) ③jar 면 java -jar, 아니면 npm start 로 다시 띄운다. BackendDeployRunner 의
     * NATIVE 부트 기동과 같은 형태다. 프로세스 치환·process substitution 같은 bashism 을 피해(임시파일 사용)
     * SSM 셸에서 안전하다.
     */
    private String buildNativeRestartCommand(ProvisionedServer server) {
        int port = server.getPort();
        long projectId = server.getProjectId();
        return String.join("\n",
                "pkill -f '/opt/app/app.jar' 2>/dev/null || true",   // jar 앱(있으면)
                "pkill -f 'node' 2>/dev/null || true",               // node 앱(있으면; 이 인스턴스엔 우리 앱만 node)
                "sleep 2",
                "cd /opt/app || exit 1",
                "aws ssm get-parameters-by-path --path /qeploy/" + projectId + "/ --with-decryption --recursive"
                        + " --query \"Parameters[].[Name,Value]\" --output text > /tmp/qeploy-env.txt 2>/dev/null || true",
                "while read -r name value; do export \"$(basename \"$name\")=$value\"; done < /tmp/qeploy-env.txt",
                "if [ -f /opt/app/app.jar ]; then",
                "  nohup java -jar /opt/app/app.jar --server.port=" + port + " > /var/log/qeploy-app.log 2>&1 &",
                "else",
                "  nohup npm start > /var/log/qeploy-app.log 2>&1 &",
                "fi");
    }

    /**
     * DOCKER 앱 재시작 명령. compose.yml 존재로 compose↔단일 run 을 가른다(로그 명령과 같은 분기).
     * 컨테이너가 hang 이든(응답만 멈춤) restart 정책이 소진돼 죽어 있든 restart 가 되살린다.
     */
    private String buildDockerRestartCommand() {
        return "if [ -f /opt/app/compose.yml ]; then "
                + "docker compose -f /opt/app/compose.yml --project-directory /opt/app restart 2>&1; "
                + "else docker restart qeploy-app 2>&1; fi";
    }
}
