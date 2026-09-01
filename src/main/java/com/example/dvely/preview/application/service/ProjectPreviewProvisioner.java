package com.example.dvely.preview.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.preview.application.port.out.PreviewDatabaseProvisioner;
import com.example.dvely.preview.application.result.PreviewDbConnection;
import com.example.dvely.preview.application.result.PreviewRuntimeConfigResult;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 프로젝트 단위 프리뷰의 실제 준비 작업(clone → install → build → serve)을 백그라운드에서 돌린다.
 *
 * <p>{@link ProjectPreviewService}와 별도의 빈인 이유는 {@code @Async} 가 프록시를 통해서만
 * 동작하기 때문이다 — 같은 클래스 안에서 호출하면 그냥 동기 실행이 되어, 사용자의 POST 요청이
 * npm install 과 build 가 끝날 때까지(수 분) 붙들리게 된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectPreviewProvisioner {

    // 실패 사유에 함께 담을 빌드 로그 줄 수. 컨테이너는 실패 직후 제거되므로 이 시점에 읽어두지
    // 않으면 사용자가 원인을 볼 방법이 없다(/logs 는 빈 응답이 된다).
    private static final int FAILURE_LOG_TAIL_LINES = 20;

    private final SpringDataPreviewSessionRepository repository;
    private final PreviewWorkspaceService workspaceService;
    private final DockerContainerService dockerService;
    private final PreviewProperties properties;
    private final PreviewRuntimeConfigService runtimeConfigService;
    private final PreviewDatabaseProvisioner databaseProvisioner;
    private final PreviewEnvComposer envComposer;

    @Async("previewExecutor")
    public void provision(String sessionId) {
        PreviewSessionEntity session = repository.findById(sessionId).orElse(null);
        if (session == null || !PreviewSessionStatus.PROVISIONING.name().equals(session.getStatus())) {
            // 요청이 겹쳐 취소됐거나(ProjectPreviewService#resolveConcurrentProvisioning) 사용자가
            // 그 사이 닫은 세션이다. 이미 컨테이너까지 정리된 상태이므로 여기서 할 일이 없다.
            return;
        }
        String containerId = session.getContainerId();
        try {
            workspaceService.prepareProject(containerId, session.getOwnerUserId(), session.getProjectId());
            workspaceService.buildIfConfigured(containerId);
            startByRuntime(session, containerId);

            // 만료는 여기서부터 다시 센다 — install/build 에 쓴 시간까지 TTL 에서 깎으면 오래 걸린
            // 프로젝트일수록 정작 볼 수 있는 시간이 짧아진다.
            session.activate(LocalDateTime.now().plus(properties.getTtl()));
            repository.save(session);
            log.info("[ProjectPreview] 프리뷰 준비 완료: sessionId={} projectId={}",
                    sessionId, session.getProjectId());
        } catch (Exception exception) {
            log.error("[ProjectPreview] 프리뷰 준비 실패: sessionId={} projectId={}",
                    sessionId, session.getProjectId(), exception);
            session.markFailed(failureReason(containerId, exception));
            repository.save(session);
            dockerService.removeContainer(containerId);
        }
    }

    /**
     * 런타임 타입에 따라 프리뷰를 서빙한다. 모든 타입이 포트 3000 에 붙으므로 게이트웨이는 무변경이다.
     *
     * <ul>
     *   <li>STATIC — 지금까지의 동작. 빌드 산출물을 {@code serve -s}.
     *   <li>NODE_SERVER — 서버형이므로 DB 를 자동 프로비저닝(best-effort)해 env 로 꽂고 앱 서버를 3000 에 실행.
     *   <li>JAVA_FULLSTACK — 실행은 다음 단계다. 지금은 명확히 실패시켜, 정적으로 조용히 잘못 서빙되지 않게 한다.
     * </ul>
     */
    private void startByRuntime(PreviewSessionEntity session, String containerId) {
        PreviewRuntimeConfigResult runtime =
                runtimeConfigService.resolveForProvision(session.getProjectId(), containerId);
        log.info("[ProjectPreview] 런타임 타입 결정: sessionId={} type={} source={}",
                session.getId(), runtime.runtimeType(), runtime.source());

        switch (runtime.runtimeTypeEnum()) {
            case STATIC -> workspaceService.startPreviewServer(containerId);
            case NODE_SERVER -> {
                PreviewDbConnection db = autoProvisionDbBestEffort(session.getProjectId(), containerId);
                List<String> env = envComposer.compose(session.getProjectId(), db);
                workspaceService.startNodeServer(containerId, runtime.startCommand(), env);
            }
            case JAVA_FULLSTACK -> throw new IllegalStateException(
                    "JAVA_FULLSTACK 프리뷰 실행은 아직 지원되지 않습니다(다음 단계).");
        }
    }

    /**
     * 서버형 프리뷰의 DB 자동 프로비저닝. 실패해도 프리뷰 전체를 죽이지 않는다 — DB 없이 서버를
     * 띄우고, DB 가 정말 필요한 앱은 자기 오류로 그 사실을 드러낸다. Docker/DB 플레이키 하나가
     * 모든 서버 프리뷰를 못 뜨게 하는 것보다 낫다.
     */
    private PreviewDbConnection autoProvisionDbBestEffort(Long projectId, String containerId) {
        try {
            return databaseProvisioner.provisionForPreview(projectId, containerId).orElse(null);
        } catch (RuntimeException exception) {
            log.warn("[ProjectPreview] DB 자동 프로비저닝 실패 — DB 없이 서버 시작: projectId={} 원인={}",
                    projectId, exception.toString());
            return null;
        }
    }

    /**
     * 사용자에게 보여줄 실패 사유. 예외 메시지만으로는 "빌드 결과 디렉터리를 찾지 못했습니다" 같은
     * 결론만 남고 왜 그렇게 됐는지가 빠지므로, 빌드 로그 꼬리를 함께 붙인다. 로그를 읽는 것 자체가
     * 실패하더라도(컨테이너가 이미 죽은 경우 등) 원래 실패 사유는 반드시 남겨야 하므로 삼킨다.
     */
    private String failureReason(String containerId, Exception exception) {
        String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "프리뷰 준비 중 오류가 발생했습니다."
                : exception.getMessage();
        String buildLog;
        try {
            buildLog = workspaceService.tailBuildLog(containerId, FAILURE_LOG_TAIL_LINES);
        } catch (Exception ignored) {
            buildLog = "";
        }
        return buildLog == null || buildLog.isBlank()
                ? message
                : message + "\n---\n" + buildLog;
    }
}
