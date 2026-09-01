package com.example.dvely.preview.application.service;

import com.example.dvely.preview.application.port.out.PreviewDatabaseProvisioner;
import com.example.dvely.preview.application.result.PreviewDbConnection;
import com.example.dvely.preview.application.result.PreviewRuntimeConfigResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 런타임 타입에 맞춰 프리뷰를 서빙한다. 프로젝트 프리뷰(버튼)와 CODE 에이전트 프리뷰가 함께 쓴다 —
 * 그래서 에이전트가 만든 백엔드도 정적이 아니라 실제 서버(NODE_SERVER/JAVA_FULLSTACK)로 뜬다.
 *
 * <p>모든 타입이 포트 3000 에 붙으므로 게이트웨이는 무변경이다. 서버형이면 DB 를 자동
 * 프로비저닝(best-effort)해 env 로 꽂는다. projectId 가 null 이면(런타임 설정은 프로젝트 단위라
 * 조회할 수 없다) 지금까지의 동작대로 정적으로 서빙한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreviewRuntimeLauncher {

    private final PreviewRuntimeConfigService runtimeConfigService;
    private final PreviewDatabaseProvisioner databaseProvisioner;
    private final PreviewEnvComposer envComposer;
    private final PreviewWorkspaceService workspaceService;

    public void launch(Long projectId, String containerId) {
        if (projectId == null) {
            workspaceService.startPreviewServer(containerId);
            return;
        }
        PreviewRuntimeConfigResult runtime = runtimeConfigService.resolveForProvision(projectId, containerId);
        log.info("[PreviewRuntime] 런타임 타입 결정: projectId={} type={} source={}",
                projectId, runtime.runtimeType(), runtime.source());

        switch (runtime.runtimeTypeEnum()) {
            case STATIC -> workspaceService.startPreviewServer(containerId);
            case NODE_SERVER -> {
                // 앱이 3000 에서 UI+API 를 모두 서빙한다.
                PreviewDbConnection db = autoProvisionDbBestEffort(projectId, containerId, runtime.dbEngine());
                List<String> env = envComposer.compose(projectId, db, 3000);
                workspaceService.startNodeServer(containerId, runtime.startCommand(), env);
            }
            case JAVA_FULLSTACK -> {
                // 정적 FE + Java BE(8080) 를 한 컨테이너에서, 내부 nginx 가 3000 에서 가른다.
                PreviewDbConnection db = autoProvisionDbBestEffort(projectId, containerId, runtime.dbEngine());
                List<String> env = envComposer.compose(projectId, db, 8080);
                workspaceService.startJavaFullstack(
                        containerId, runtime.startCommand(), env, runtime.apiPathPrefix());
            }
        }
    }

    /**
     * 서버형 프리뷰의 DB 자동 프로비저닝. 실패해도 프리뷰 전체를 죽이지 않는다 — DB 없이 서버를
     * 띄우고, DB 가 정말 필요한 앱은 자기 오류로 그 사실을 드러낸다. Docker/DB 플레이키 하나가
     * 모든 서버 프리뷰를 못 뜨게 하는 것보다 낫다.
     */
    private PreviewDbConnection autoProvisionDbBestEffort(Long projectId, String containerId, String dbEngine) {
        try {
            return databaseProvisioner.provisionForPreview(projectId, containerId, dbEngine).orElse(null);
        } catch (RuntimeException exception) {
            log.warn("[PreviewRuntime] DB 자동 프로비저닝 실패 — DB 없이 서버 시작: projectId={} 원인={}",
                    projectId, exception.toString());
            return null;
        }
    }
}
