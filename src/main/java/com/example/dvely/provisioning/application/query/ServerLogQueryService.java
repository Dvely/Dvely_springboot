package com.example.dvely.provisioning.application.query;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.domain.value.ServerLogSource;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.SsmRunCommandClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 배포된 EC2 서버의 최근 로그를 조회한다(SSM Run Command). 순수 DB 조회인 {@link ServerProvisioningQueryService}
 * 와 달리 <b>느린 외부 호출</b>(SSM SendCommand → 폴링)이라 상시 폴링용이 아니고, 사용자가 로그 버튼을 눌러
 * 요청할 때만 쓴다 — 그래서 분리했다.
 *
 * <p>실행 형태(NATIVE=파일 로그 / DOCKER=컨테이너·compose 로그)와 소스(APP·BOOT·CADDY)에 따라 인스턴스에서
 * 돌릴 셸 명령을 조립해 SSM 으로 실행하고 출력을 돌려준다. 살아있는 인스턴스에서만 가능하다(로그를 어디로도
 * 실어 나르지 않으므로 — 인스턴스가 종료되면 로그도 사라진다).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerLogQueryService {

    private final ProvisionedServerRepository serverRepository;
    private final ProjectRepository projectRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final SsmRunCommandClient ssmRunCommandClient;

    public record ServerLogs(Long serverId, String source, String content) {}

    public ServerLogs fetchLogs(Long ownerUserId, Long serverId, ServerLogSource source) {
        ProvisionedServer server = serverRepository.findById(serverId)
                .orElseThrow(() -> new NotFoundException("서버를 찾을 수 없습니다. serverId=" + serverId));
        // 소유권 — terminate 와 같은 2단 검증.
        projectRepository.findByIdAndOwnerUserId(server.getProjectId(), ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "서버를 찾을 수 없거나 접근 권한이 없습니다. serverId=" + serverId));

        boolean liveInstance = server.getStatus() == ServerStatus.RUNNING
                || server.getStatus() == ServerStatus.PROVISIONING;
        // 종료된 서버라도 부트 타임아웃 때 보존해 둔 부트 로그가 있으면 그걸 돌려준다 — "왜 안 떴나"는
        // 실패한 뒤에 보는 정보라, 인스턴스가 사라져 라이브 조회가 막힌 뒤에도 남아 있어야 뜻이 있다.
        if (!liveInstance && source == ServerLogSource.BOOT && server.hasBootDiagnostics()) {
            return new ServerLogs(serverId, source.name(),
                    "[종료된 서버의 보존된 부트 로그]\n" + server.getBootDiagnostics());
        }
        if (!liveInstance) {
            throw new IllegalStateException(
                    "실행 중인 서버만 로그를 조회할 수 있습니다. 종료된 인스턴스의 로그는 남지 않습니다. status="
                            + server.getStatus());
        }
        if (server.getInstanceId() == null) {
            throw new IllegalStateException("아직 인스턴스가 없어 로그를 조회할 수 없습니다.");
        }
        CloudConnection connection = server.getCloudConnectionId() == null ? null
                : cloudConnectionRepository.findById(server.getCloudConnectionId()).orElse(null);
        if (connection == null) {
            throw new NotFoundException("서버의 클라우드 연결을 찾을 수 없습니다.");
        }

        String output = ssmRunCommandClient.runShellCommand(
                connection, server.getInstanceId(), buildCommand(server, source));
        log.info("서버 로그 조회: serverId={} source={} instanceId={} 길이={}",
                serverId, source, server.getInstanceId(), output == null ? 0 : output.length());
        return new ServerLogs(serverId, source.name(), output);
    }

    /**
     * 실행 형태·소스별 "최근 로그를 뽑는" 셸 명령. DOCKER 는 compose.yml 존재로 compose↔단일 run 을 가른다
     * (BackendDeployRunner 가 compose 를 쓰면 그 파일이 생기므로 실행 형태 조건을 재구현하지 않아도 된다).
     */
    private String buildCommand(ProvisionedServer server, ServerLogSource source) {
        return switch (source) {
            case BOOT -> SsmRunCommandClient.BOOT_LOG_TAIL;
            case CADDY -> "sudo tail -n 200 /var/log/qeploy-caddy.log 2>/dev/null || echo 'Caddy 로그 없음(도메인 미연결일 수 있음)'";
            case APP -> server.getDeployMode() == ServerDeployMode.DOCKER
                    ? "if [ -f /opt/app/compose.yml ]; then "
                            + "docker compose -f /opt/app/compose.yml --project-directory /opt/app logs --tail 200 2>&1; "
                            + "else docker logs --tail 200 qeploy-app 2>&1 "
                            + "|| docker logs --tail 200 \"$(docker ps -q | head -1)\" 2>&1; fi"
                    : "tail -n 200 /var/log/qeploy-app.log 2>/dev/null || echo '앱 로그 없음(아직 기동 전이거나 파일 없음)'";
        };
    }
}
