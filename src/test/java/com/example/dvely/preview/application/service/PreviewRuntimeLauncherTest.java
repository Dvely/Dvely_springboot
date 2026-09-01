package com.example.dvely.preview.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.preview.application.port.out.PreviewDatabaseProvisioner;
import com.example.dvely.preview.application.result.PreviewDbConnection;
import com.example.dvely.preview.application.result.PreviewRuntimeConfigResult;
import com.example.dvely.preview.domain.value.PreviewRuntimeType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 런타임 타입별 서빙 분기. 프로젝트 프리뷰와 CODE 에이전트 프리뷰가 이 런처를 공유하므로, 여기서
 * STATIC/NODE_SERVER/JAVA_FULLSTACK 과 projectId=null(정적) 을 한자리에서 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreviewRuntimeLauncherTest {

    private static final Long PROJECT_ID = 11L;
    private static final String CONTAINER_ID = "container-1";

    @Mock private PreviewRuntimeConfigService runtimeConfigService;
    @Mock private PreviewDatabaseProvisioner databaseProvisioner;
    @Mock private PreviewEnvComposer envComposer;
    @Mock private PreviewWorkspaceService workspaceService;

    @InjectMocks private PreviewRuntimeLauncher launcher;

    @Test
    void staticRuntimeServesStatically() {
        when(runtimeConfigService.resolveForProvision(PROJECT_ID, CONTAINER_ID))
                .thenReturn(PreviewRuntimeConfigResult.defaultStatic(PROJECT_ID));

        launcher.launch(PROJECT_ID, CONTAINER_ID);

        verify(workspaceService).startPreviewServer(CONTAINER_ID);
        verify(workspaceService, never()).startNodeServer(any(), any(), any());
        verify(databaseProvisioner, never()).provisionForPreview(any(), any(), any());
    }

    /** projectId 가 null 이면(에이전트 세션 등 프로젝트가 없는 경우) 런타임 설정 조회 없이 정적으로. */
    @Test
    void nullProjectServesStaticallyWithoutConfigLookup() {
        launcher.launch(null, CONTAINER_ID);

        verify(workspaceService).startPreviewServer(CONTAINER_ID);
        verify(runtimeConfigService, never()).resolveForProvision(any(), any());
    }

    @Test
    void nodeServerAutoProvisionsDbAndStartsAppServerOn3000() {
        when(runtimeConfigService.resolveForProvision(PROJECT_ID, CONTAINER_ID))
                .thenReturn(nodeConfig("npm start"));
        PreviewDbConnection db = new PreviewDbConnection("MYSQL", "db", 3306, "app", "app", "pw");
        when(databaseProvisioner.provisionForPreview(PROJECT_ID, CONTAINER_ID, "MYSQL")).thenReturn(Optional.of(db));
        List<String> env = List.of("DB_HOST=db", "PORT=3000");
        when(envComposer.compose(PROJECT_ID, db, 3000)).thenReturn(env);

        launcher.launch(PROJECT_ID, CONTAINER_ID);

        verify(databaseProvisioner).provisionForPreview(PROJECT_ID, CONTAINER_ID, "MYSQL");
        verify(workspaceService).startNodeServer(CONTAINER_ID, "npm start", env);
        verify(workspaceService, never()).startPreviewServer(any());
    }

    /** DB 자동 프로비저닝 실패는 프리뷰를 죽이지 않는다 — DB 없이 서버를 그대로 띄운다. */
    @Test
    void nodeServerStartsWithoutDbWhenAutoProvisionFails() {
        when(runtimeConfigService.resolveForProvision(PROJECT_ID, CONTAINER_ID))
                .thenReturn(nodeConfig(null));
        when(databaseProvisioner.provisionForPreview(PROJECT_ID, CONTAINER_ID, "MYSQL"))
                .thenThrow(new IllegalStateException("DB 컨테이너가 준비되지 않았습니다."));
        List<String> env = List.of("PORT=3000");
        when(envComposer.compose(eq(PROJECT_ID), any(), eq(3000))).thenReturn(env);

        launcher.launch(PROJECT_ID, CONTAINER_ID);

        verify(workspaceService).startNodeServer(CONTAINER_ID, null, env);
    }

    /** JAVA_FULLSTACK: 정적 FE + Java BE(8080). env 포트는 8080, startJavaFullstack 호출. */
    @Test
    void javaFullstackAutoProvisionsDbAndStartsNginxRoutedBackend() {
        when(runtimeConfigService.resolveForProvision(PROJECT_ID, CONTAINER_ID))
                .thenReturn(new PreviewRuntimeConfigResult(PROJECT_ID,
                        PreviewRuntimeType.JAVA_FULLSTACK.name(), "./gradlew bootRun", "/api", null, "MYSQL", "STORED"));
        PreviewDbConnection db = new PreviewDbConnection("MYSQL", "db", 3306, "app", "app", "pw");
        when(databaseProvisioner.provisionForPreview(PROJECT_ID, CONTAINER_ID, "MYSQL")).thenReturn(Optional.of(db));
        List<String> env = List.of("SERVER_PORT=8080");
        when(envComposer.compose(PROJECT_ID, db, 8080)).thenReturn(env);

        launcher.launch(PROJECT_ID, CONTAINER_ID);

        verify(workspaceService).startJavaFullstack(CONTAINER_ID, "./gradlew bootRun", env, "/api");
        verify(workspaceService, never()).startPreviewServer(any());
        verify(workspaceService, never()).startNodeServer(any(), any(), any());
    }

    private PreviewRuntimeConfigResult nodeConfig(String startCommand) {
        return new PreviewRuntimeConfigResult(PROJECT_ID, PreviewRuntimeType.NODE_SERVER.name(),
                startCommand, "/api", null, "MYSQL", "STORED");
    }
}
