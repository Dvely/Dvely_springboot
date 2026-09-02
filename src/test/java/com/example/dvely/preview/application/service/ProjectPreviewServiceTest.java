package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.common.exception.PreviewEnvironmentUnavailableException;
import com.example.dvely.config.CorsProperties;
import com.example.dvely.preview.application.result.ProjectPreviewSessionResult;
import com.example.dvely.preview.application.service.ProjectPreviewService.ProvisionOutcome;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.config.PreviewGatewayUrlResolver;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 프로젝트에 들어왔을 때 "지금 상태"를 보여주는 경로의 규칙을 고정한다: 살아 있으면 붙고, 없으면
 * 사용자의 명시적 요청으로만 새로 띄우며, 겹친 요청은 컨테이너를 두 개 만들지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectPreviewServiceTest {

    private static final Long PROJECT_ID = 11L;
    private static final Long USER_ID = 1L;

    @Mock private SpringDataPreviewSessionRepository repository;
    @Mock private ProjectRepository projectRepository;
    @Mock private DockerContainerService dockerService;
    @Mock private ProjectPreviewProvisioner provisioner;
    @Mock private PreviewRuntimeConfigService runtimeConfigService;

    private ProjectPreviewService service;
    private final List<PreviewSessionEntity> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PreviewProperties properties = new PreviewProperties();
        properties.setGatewayBaseUrl("https://preview.qeploy.test");
        properties.setTtl(Duration.ofMinutes(30));
        PreviewGatewayUrlResolver gatewayUrlResolver = new PreviewGatewayUrlResolver(
                properties, new CorsProperties(List.of(), List.of()));
        service = new ProjectPreviewService(
                repository, projectRepository, dockerService, properties, gatewayUrlResolver,
                provisioner, runtimeConfigService);
        // 저장된 런타임 타입 없음 → 기본 메모리로 컨테이너 생성.
        when(runtimeConfigService.storedRuntimeType(any())).thenReturn(Optional.empty());
        // 목 생성/스터빙을 when(...) 인자 안에서 하면 Mockito 가 중첩 스터빙으로 보고 실패한다.
        Project connected = project("owner/repo");
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(PROJECT_ID, USER_ID))
                .thenReturn(Optional.of(connected));
        when(repository.save(any(PreviewSessionEntity.class))).thenAnswer(invocation -> {
            PreviewSessionEntity entity = invocation.getArgument(0);
            if (entity.getCreatedAt() == null) {
                // @CreationTimestamp 대역 — 동시 요청 판정이 이 값으로 이뤄지므로 비워 둘 수 없다.
                setCreatedAt(entity, LocalDateTime.now());
            }
            saved.add(entity);
            return entity;
        });
    }

    @Test
    void returnsNothingWhenTheProjectHasNoPreviewYet() {
        when(repository.findFirstByProjectIdAndOwnerUserIdAndStatusInOrderByLastAccessedAtDesc(
                eq(PROJECT_ID), eq(USER_ID), any())).thenReturn(Optional.empty());

        assertThat(service.findCurrent(PROJECT_ID, USER_ID)).isEmpty();
    }

    @Test
    void unknownProjectIsNotFoundRatherThanAnEmptyPreview() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(99L, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findCurrent(99L, USER_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("99");
    }

    /**
     * 세션 행은 컨테이너보다 오래 살 수 있다(데몬 재시작, 외부 정리). 행만 보고 URL 을 내려주면 FE 가
     * 죽은 주소를 iframe 에 걸고 502 를 본다 — 여기서 정리해 "없음"으로 답해야 사용자가 다시 띄울 수
     * 있다.
     */
    @Test
    void dropsAnActiveSessionWhoseContainerIsGoneInsteadOfHandingOutADeadUrl() {
        PreviewSessionEntity active = session(PreviewSessionStatus.ACTIVE, "container-1", "task-1");
        when(repository.findFirstByProjectIdAndOwnerUserIdAndStatusInOrderByLastAccessedAtDesc(
                eq(PROJECT_ID), eq(USER_ID), any())).thenReturn(Optional.of(active));
        when(dockerService.isContainerRunning("container-1")).thenReturn(false);

        assertThat(service.findCurrent(PROJECT_ID, USER_ID)).isEmpty();
        assertThat(active.getStatus()).isEqualTo(PreviewSessionStatus.EXPIRED.name());
        verify(dockerService).removeContainer("container-1");
    }

    /** 준비 중인 세션은 아직 열 수 없는 주소를 숨긴 채로 상태만 보여준다. */
    @Test
    void reportsProvisioningWithoutAUrlToOpen() {
        when(repository.findFirstByProjectIdAndOwnerUserIdAndStatusInOrderByLastAccessedAtDesc(
                eq(PROJECT_ID), eq(USER_ID), any()))
                .thenReturn(Optional.of(session(PreviewSessionStatus.PROVISIONING, "container-1", null)));

        ProjectPreviewSessionResult result = service.findCurrent(PROJECT_ID, USER_ID).orElseThrow();

        assertThat(result.status()).isEqualTo(PreviewSessionStatus.PROVISIONING.name());
        assertThat(result.previewUrl()).isNull();
        assertThat(result.taskId()).isNull();
    }

    @Test
    void attachesToALiveSessionInsteadOfStartingASecondContainer() {
        PreviewSessionEntity active = session(PreviewSessionStatus.ACTIVE, "container-1", "task-1");
        when(repository.findFirstByProjectIdAndOwnerUserIdAndStatusInOrderByLastAccessedAtDesc(
                eq(PROJECT_ID), eq(USER_ID), any())).thenReturn(Optional.of(active));
        when(dockerService.isContainerRunning("container-1")).thenReturn(true);

        ProvisionOutcome outcome = service.provision(PROJECT_ID, USER_ID);

        assertThat(outcome.started()).isFalse();
        assertThat(outcome.session().previewUrl()).isNotNull();
        verify(dockerService, never()).createAndStartContainer(any(), anyString(), any(), any(), any(), anyLong());
        verify(provisioner, never()).provision(anyString());
    }

    @Test
    void doesNotStartASecondProvisioningWhileOneIsAlreadyRunning() {
        when(repository.findFirstByProjectIdAndOwnerUserIdAndStatusInOrderByLastAccessedAtDesc(
                eq(PROJECT_ID), eq(USER_ID), any()))
                .thenReturn(Optional.of(session(PreviewSessionStatus.PROVISIONING, "container-1", null)));

        ProvisionOutcome outcome = service.provision(PROJECT_ID, USER_ID);

        assertThat(outcome.started()).isTrue();
        verify(dockerService, never()).createAndStartContainer(any(), anyString(), any(), any(), any(), anyLong());
        verify(provisioner, never()).provision(anyString());
    }

    @Test
    void startsAProjectScopedSessionWithNoTaskAndHandsItToTheProvisioner() {
        when(repository.findFirstByProjectIdAndOwnerUserIdAndStatusInOrderByLastAccessedAtDesc(
                eq(PROJECT_ID), eq(USER_ID), any())).thenReturn(Optional.empty());
        when(dockerService.createAndStartContainer(eq(USER_ID), anyString(), eq(PROJECT_ID), eq(null), eq(null), anyLong()))
                .thenReturn("container-new");
        when(dockerService.getMappedPort("container-new")).thenReturn(32770);
        when(repository.findByProjectIdAndOwnerUserIdAndStatusIn(eq(PROJECT_ID), eq(USER_ID), any()))
                .thenAnswer(invocation -> List.of(savedSession()));

        ProvisionOutcome outcome = service.provision(PROJECT_ID, USER_ID);

        assertThat(outcome.started()).isTrue();
        assertThat(outcome.session().status()).isEqualTo(PreviewSessionStatus.PROVISIONING.name());
        assertThat(outcome.session().taskId()).isNull();
        // 준비가 끝나기 전 주소는 게이트웨이가 열어주지 않으므로 내려주지 않는다.
        assertThat(outcome.session().previewUrl()).isNull();
        verify(provisioner).provision(savedSession().getId());
    }

    /**
     * Docker 가 없는 서버(설치 누락·소켓 권한 없음)에서 처음 실패하는 지점이다. 그대로 흘리면
     * catch-all 500 "서버 내부 오류"가 되어, FE 도 운영자도 서버에 붙기 전에는 원인을 알 수 없다.
     */
    @Test
    void aDockerFailureIsReportedAsAnUnavailableEnvironmentInsteadOfAnOpaqueError() {
        when(repository.findFirstByProjectIdAndOwnerUserIdAndStatusInOrderByLastAccessedAtDesc(
                eq(PROJECT_ID), eq(USER_ID), any())).thenReturn(Optional.empty());
        when(dockerService.createAndStartContainer(eq(USER_ID), anyString(), eq(PROJECT_ID), eq(null), eq(null), anyLong()))
                .thenThrow(new RuntimeException("Cannot connect to the Docker daemon at unix:///var/run/docker.sock"));

        assertThatThrownBy(() -> service.provision(PROJECT_ID, USER_ID))
                .isInstanceOf(PreviewEnvironmentUnavailableException.class)
                .hasMessageContaining("Docker")
                .hasMessageContaining("docker.sock");
        verify(provisioner, never()).provision(anyString());
    }

    @Test
    void refusesToProvisionAProjectWithNoConnectedRepository() {
        Project disconnected = project(null);
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(PROJECT_ID, USER_ID))
                .thenReturn(Optional.of(disconnected));

        assertThatThrownBy(() -> service.provision(PROJECT_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("저장소");
        verify(dockerService, never()).createAndStartContainer(any(), anyString(), any(), any(), any(), anyLong());
    }

    /**
     * 버튼 더블클릭으로 두 요청이 나란히 "살아 있는 세션 없음"을 본 상황. 나중에 만들어진 쪽이 스스로
     * 물러나고 컨테이너까지 즉시 반납해야, 1 GiB 컨테이너가 TTL 이 다할 때까지 놀지 않는다.
     */
    @Test
    void aLosingConcurrentRequestCancelsItselfAndReleasesItsContainer() {
        when(repository.findFirstByProjectIdAndOwnerUserIdAndStatusInOrderByLastAccessedAtDesc(
                eq(PROJECT_ID), eq(USER_ID), any())).thenReturn(Optional.empty());
        when(dockerService.createAndStartContainer(eq(USER_ID), anyString(), eq(PROJECT_ID), eq(null), eq(null), anyLong()))
                .thenReturn("container-late");
        when(dockerService.getMappedPort("container-late")).thenReturn(32771);
        PreviewSessionEntity earlier = session(PreviewSessionStatus.PROVISIONING, "container-early", null);
        when(repository.findByProjectIdAndOwnerUserIdAndStatusIn(eq(PROJECT_ID), eq(USER_ID), any()))
                .thenAnswer(invocation -> List.of(earlier, savedSession()));

        ProvisionOutcome outcome = service.provision(PROJECT_ID, USER_ID);

        assertThat(outcome.session().sessionId()).isEqualTo(earlier.getId());
        assertThat(outcome.started()).isTrue();
        verify(dockerService).removeContainer("container-late");
        verify(provisioner, never()).provision(anyString());
    }

    /** 서비스가 이번 호출에서 새로 만든 세션(첫 save). 동시 요청 판정의 대상이 되는 그 행이다. */
    private PreviewSessionEntity savedSession() {
        return saved.get(0);
    }

    private PreviewSessionEntity session(PreviewSessionStatus status, String containerId, String taskId) {
        PreviewSessionEntity entity = new PreviewSessionEntity(
                "session-" + containerId,
                "token-" + containerId,
                USER_ID,
                PROJECT_ID,
                null,
                taskId,
                containerId,
                32768,
                "https://preview.qeploy.test/api/v1/previews/session-" + containerId + "/token/",
                LocalDateTime.now().plusMinutes(30),
                status
        );
        // @CreationTimestamp 는 실제 저장 시점에 채워지므로 단위 테스트에서는 직접 넣어준다.
        setCreatedAt(entity, LocalDateTime.now().minusMinutes(1));
        return entity;
    }

    private void setCreatedAt(PreviewSessionEntity entity, LocalDateTime createdAt) {
        try {
            var field = PreviewSessionEntity.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(entity, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Project project(String sourceRepository) {
        Project project = mock(Project.class);
        when(project.getSourceRepository()).thenReturn(sourceRepository);
        return project;
    }
}
