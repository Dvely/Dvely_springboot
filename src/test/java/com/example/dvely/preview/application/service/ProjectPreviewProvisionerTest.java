package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.preview.application.port.out.PreviewDatabaseProvisioner;
import com.example.dvely.preview.application.result.PreviewDbConnection;
import com.example.dvely.preview.application.result.PreviewRuntimeConfigResult;
import com.example.dvely.preview.domain.value.PreviewRuntimeType;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectPreviewProvisionerTest {

    private static final String SESSION_ID = "session-1";
    private static final String CONTAINER_ID = "container-1";
    private static final Long PROJECT_ID = 11L;

    @Mock private SpringDataPreviewSessionRepository repository;
    @Mock private PreviewWorkspaceService workspaceService;
    @Mock private DockerContainerService dockerService;
    @Mock private PreviewRuntimeConfigService runtimeConfigService;
    @Mock private PreviewDatabaseProvisioner databaseProvisioner;
    @Mock private PreviewEnvComposer envComposer;

    private ProjectPreviewProvisioner provisioner;

    @BeforeEach
    void setUp() {
        PreviewProperties properties = new PreviewProperties();
        properties.setTtl(Duration.ofMinutes(30));
        provisioner = new ProjectPreviewProvisioner(repository, workspaceService, dockerService,
                properties, runtimeConfigService, databaseProvisioner, envComposer);
        when(repository.save(any(PreviewSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // 기본은 STATIC — 기존 정적 프리뷰 동작을 그대로 검증하는 테스트들이 쓴다.
        when(runtimeConfigService.resolveForProvision(anyLong(), anyString()))
                .thenReturn(PreviewRuntimeConfigResult.defaultStatic(PROJECT_ID));
    }

    @Test
    void staticRuntimePreparesBuildsAndServesBeforeMarkingTheSessionActive() {
        PreviewSessionEntity session = provisioningSession();
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        provisioner.provision(SESSION_ID);

        verify(workspaceService).prepareProject(CONTAINER_ID, 1L, PROJECT_ID);
        verify(workspaceService).buildIfConfigured(CONTAINER_ID);
        verify(workspaceService).startPreviewServer(CONTAINER_ID);
        verify(workspaceService, never()).startNodeServer(anyString(), any(), any());
        assertThat(session.getStatus()).isEqualTo(PreviewSessionStatus.ACTIVE.name());
        assertThat(session.getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(25));
    }

    /**
     * NODE_SERVER: 서버형이므로 DB 를 자동 프로비저닝해 env 로 꽂고, 정적 serve 대신 앱 서버를 띄운다.
     */
    @Test
    void nodeServerRuntimeAutoProvisionsDbAndStartsTheAppServer() {
        PreviewSessionEntity session = provisioningSession();
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(runtimeConfigService.resolveForProvision(PROJECT_ID, CONTAINER_ID))
                .thenReturn(nodeConfig("npm start"));
        PreviewDbConnection db = new PreviewDbConnection("MYSQL", "db", 3306, "app", "app", "pw");
        when(databaseProvisioner.provisionForPreview(PROJECT_ID, CONTAINER_ID)).thenReturn(Optional.of(db));
        List<String> env = List.of("DB_HOST=db", "PORT=3000");
        when(envComposer.compose(PROJECT_ID, db)).thenReturn(env);

        provisioner.provision(SESSION_ID);

        verify(databaseProvisioner).provisionForPreview(PROJECT_ID, CONTAINER_ID);
        verify(workspaceService).startNodeServer(CONTAINER_ID, "npm start", env);
        verify(workspaceService, never()).startPreviewServer(anyString());
        assertThat(session.getStatus()).isEqualTo(PreviewSessionStatus.ACTIVE.name());
    }

    /**
     * DB 자동 프로비저닝 실패는 프리뷰 전체를 죽이지 않는다 — DB 없이(env 에 DB 값 없이) 서버를
     * 그대로 띄운다. Docker/DB 플레이키 하나가 모든 서버 프리뷰를 막지 않게 하기 위해서다.
     */
    @Test
    void nodeServerStartsWithoutDbWhenAutoProvisionFails() {
        PreviewSessionEntity session = provisioningSession();
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(runtimeConfigService.resolveForProvision(PROJECT_ID, CONTAINER_ID))
                .thenReturn(nodeConfig(null));
        when(databaseProvisioner.provisionForPreview(PROJECT_ID, CONTAINER_ID))
                .thenThrow(new IllegalStateException("DB 컨테이너가 준비되지 않았습니다."));
        List<String> env = List.of("PORT=3000");
        when(envComposer.compose(eq(PROJECT_ID), any())).thenReturn(env);

        provisioner.provision(SESSION_ID);

        // startCommand 가 null 이면 서비스가 npm start 로 실행하므로 여기서는 null 을 그대로 전달한다.
        verify(workspaceService).startNodeServer(CONTAINER_ID, null, env);
        assertThat(session.getStatus()).isEqualTo(PreviewSessionStatus.ACTIVE.name());
    }

    /** JAVA_FULLSTACK 실행은 다음 단계다 — 지금은 명확히 실패시켜 정적으로 잘못 서빙되지 않게 한다. */
    @Test
    void javaFullstackFailsClearlyForNow() {
        PreviewSessionEntity session = provisioningSession();
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(runtimeConfigService.resolveForProvision(PROJECT_ID, CONTAINER_ID))
                .thenReturn(new PreviewRuntimeConfigResult(PROJECT_ID,
                        PreviewRuntimeType.JAVA_FULLSTACK.name(), null, "/api", null, "DETECTED"));

        provisioner.provision(SESSION_ID);

        assertThat(session.getStatus()).isEqualTo(PreviewSessionStatus.FAILED.name());
        assertThat(session.getFailureReason()).contains("아직 지원되지 않습니다");
        verify(dockerService).removeContainer(CONTAINER_ID);
        verify(workspaceService, never()).startPreviewServer(anyString());
        verify(workspaceService, never()).startNodeServer(anyString(), any(), any());
    }

    /**
     * 실패 사유는 컨테이너를 지우기 전에 로그까지 함께 담아야 한다 — 지운 뒤에는
     * {@code /preview-sessions/{id}/logs} 가 빈 응답이라 사용자가 원인을 볼 방법이 없다.
     */
    @Test
    void failedProvisioningKeepsTheReasonWithBuildLogAndReleasesTheContainer() {
        PreviewSessionEntity session = provisioningSession();
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        doThrow(new IllegalStateException("빌드 결과 디렉터리를 찾지 못했습니다."))
                .when(workspaceService).startPreviewServer(CONTAINER_ID);
        when(workspaceService.tailBuildLog(anyString(), anyInt()))
                .thenReturn("npm ERR! Missing script: \"build\"");

        provisioner.provision(SESSION_ID);

        assertThat(session.getStatus()).isEqualTo(PreviewSessionStatus.FAILED.name());
        assertThat(session.getFailureReason())
                .contains("빌드 결과 디렉터리를 찾지 못했습니다.")
                .contains("Missing script");
        verify(dockerService).removeContainer(CONTAINER_ID);
    }

    /** 요청이 겹쳐 취소됐거나 사용자가 닫은 세션 — 컨테이너까지 이미 정리된 상태라 할 일이 없다. */
    @Test
    void skipsASessionThatIsNoLongerProvisioning() {
        PreviewSessionEntity cancelled = provisioningSession();
        cancelled.close(PreviewSessionStatus.CLOSED);
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(cancelled));

        provisioner.provision(SESSION_ID);

        verify(workspaceService, never()).prepareProject(anyString(), anyLong(), anyLong());
        verify(dockerService, never()).removeContainer(anyString());
    }

    private PreviewRuntimeConfigResult nodeConfig(String startCommand) {
        return new PreviewRuntimeConfigResult(PROJECT_ID, PreviewRuntimeType.NODE_SERVER.name(),
                startCommand, "/api", null, "STORED");
    }

    private PreviewSessionEntity provisioningSession() {
        return new PreviewSessionEntity(
                SESSION_ID,
                "token",
                1L,
                PROJECT_ID,
                null,
                null,
                CONTAINER_ID,
                32768,
                "https://preview.qeploy.test/api/v1/previews/session-1/token/",
                LocalDateTime.now().plusMinutes(30),
                PreviewSessionStatus.PROVISIONING
        );
    }
}
