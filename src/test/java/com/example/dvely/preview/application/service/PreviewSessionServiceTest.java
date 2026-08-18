package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.auth.infrastructure.config.JwtProperties;
import com.example.dvely.config.CorsProperties;
import com.example.dvely.preview.infrastructure.config.PreviewGatewayUrlResolver;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import com.example.dvely.preview.infrastructure.security.PreviewAccessCookies;
import com.example.dvely.common.exception.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PreviewSessionServiceTest {

    // ── Agent 세션도 PROVISIONING 을 거친다 ────────────────────────────────────────────

    @Test
    void agentSessionStartsAsProvisioningNotActive() {
        // acquire 는 CodeAgentService 가 LLM 작업을 시작하기 전에 불린다. 그 시점에 ACTIVE 로
        // 만들어두면 FE 가 "열면 보인다"로 읽고 iframe 을 붙였다가 빌드가 끝날 때까지 502 만 본다.
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        DockerContainerService dockerService = mock(DockerContainerService.class);
        TaskStore taskStore = mock(TaskStore.class);
        PreviewSessionService service = new PreviewSessionService(
                repository, dockerService, taskStore, properties(), gatewayUrlResolver(), accessCookies()
        );
        when(taskStore.get("task-1")).thenReturn(task());
        when(repository.findByTaskIdAndStatus("task-1", PreviewSessionStatus.ACTIVE.name()))
                .thenReturn(Optional.empty());
        when(dockerService.createAndStartContainer(eq(1L), any(String.class), eq(11L), eq(21L), eq("task-1")))
                .thenReturn("container-1");
        when(dockerService.getMappedPort("container-1")).thenReturn(32768);
        when(repository.save(any(PreviewSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.acquire("task-1");

        org.mockito.ArgumentCaptor<PreviewSessionEntity> captor =
                org.mockito.ArgumentCaptor.forClass(PreviewSessionEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PreviewSessionStatus.PROVISIONING.name());
    }

    @Test
    void markServingPromotesProvisioningSessionToActive() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository, mock(DockerContainerService.class), mock(TaskStore.class),
                properties(), gatewayUrlResolver(), accessCookies()
        );
        PreviewSessionEntity provisioning = provisioningSession();
        when(repository.findByTaskIdAndStatus("task-1", PreviewSessionStatus.PROVISIONING.name()))
                .thenReturn(Optional.of(provisioning));

        service.markServing("task-1");

        assertThat(provisioning.getStatus()).isEqualTo(PreviewSessionStatus.ACTIVE.name());
        verify(repository).save(provisioning);
    }

    @Test
    void markServeFailedClosesTheSessionWithAReasonInsteadOfLeavingItProvisioning() {
        // PROVISIONING 인 채로 두면 FE 가 준비 중 스켈레톤을 무한히 돌린다.
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository, mock(DockerContainerService.class), mock(TaskStore.class),
                properties(), gatewayUrlResolver(), accessCookies()
        );
        PreviewSessionEntity provisioning = provisioningSession();
        when(repository.findByTaskIdAndStatus("task-1", PreviewSessionStatus.PROVISIONING.name()))
                .thenReturn(Optional.of(provisioning));

        service.markServeFailed("task-1", "빌드는 끝났지만 프리뷰 서버를 시작하지 못했습니다.");

        assertThat(provisioning.getStatus()).isEqualTo(PreviewSessionStatus.FAILED.name());
        assertThat(provisioning.getFailureReason()).contains("프리뷰 서버를 시작하지 못했습니다");
        verify(repository).save(provisioning);
    }

    @Test
    void findByTaskIdStaysEmptyWhileProvisioning() {
        // ChangeService / ResultApprovalGate / RepositoryBindingGate 가 이 조회를 쓴다. 전부
        // startPreviewServer 이후에 도는 코드라 그때는 ACTIVE 지만, 순서가 뒤집히면 빈다는 것을
        // 고정해둔다.
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository, mock(DockerContainerService.class), mock(TaskStore.class),
                properties(), gatewayUrlResolver(), accessCookies()
        );
        when(repository.findByTaskIdAndStatus("task-1", PreviewSessionStatus.ACTIVE.name()))
                .thenReturn(Optional.empty());

        assertThat(service.findByTaskId("task-1")).isEmpty();
    }

    private PreviewSessionEntity provisioningSession() {
        return new PreviewSessionEntity(
                "session-1", "token-1", 1L, 11L, 21L, "task-1", "container-1", 32768,
                "https://preview.qeploy.test/api/v1/previews/session-1/token-1/",
                java.time.LocalDateTime.now().plusMinutes(30),
                PreviewSessionStatus.PROVISIONING
        );
    }

    @Test
    void createsTaskScopedGatewaySession() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        DockerContainerService dockerService = mock(DockerContainerService.class);
        TaskStore taskStore = mock(TaskStore.class);
        PreviewProperties properties = properties();
        PreviewSessionService service = new PreviewSessionService(
                repository,
                dockerService,
                taskStore,
                properties,
                gatewayUrlResolver(),
                accessCookies()
        );
        when(taskStore.get("task-1")).thenReturn(task());
        when(repository.findByTaskIdAndStatus("task-1", PreviewSessionStatus.ACTIVE.name()))
                .thenReturn(Optional.empty());
        when(dockerService.createAndStartContainer(
                eq(1L),
                any(String.class),
                eq(11L),
                eq(21L),
                eq("task-1")
        )).thenReturn("container-1");
        when(dockerService.getMappedPort("container-1")).thenReturn(32768);
        when(repository.save(any(PreviewSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PreviewSessionInfo result = service.acquire("task-1");

        assertThat(result.taskId()).isEqualTo("task-1");
        assertThat(result.projectId()).isEqualTo(11L);
        assertThat(result.conversationId()).isEqualTo(21L);
        assertThat(result.publicUrl()).startsWith("https://preview.qeploy.test/api/v1/previews/");
        assertThat(result.publicUrl()).doesNotContain("32768", "localhost");
    }

    @Test
    void cleanupRemovesExpiredContainer() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        DockerContainerService dockerService = mock(DockerContainerService.class);
        PreviewSessionService service = new PreviewSessionService(
                repository,
                dockerService,
                mock(TaskStore.class),
                properties(),
                gatewayUrlResolver(),
                accessCookies()
        );
        PreviewSessionEntity expired = new PreviewSessionEntity(
                "session-1",
                "token",
                1L,
                11L,
                21L,
                "task-1",
                "container-1",
                32768,
                "https://preview.qeploy.test/session-1/",
                LocalDateTime.now().minusMinutes(1)
        );
        when(repository.findByStatusInAndExpiresAtBefore(
                any(),
                any(LocalDateTime.class)
        )).thenReturn(List.of(expired));
        when(repository.save(expired)).thenReturn(expired);

        service.cleanupExpired();

        assertThat(expired.getStatus()).isEqualTo(PreviewSessionStatus.EXPIRED.name());
        verify(dockerService).removeContainer("container-1");
    }

    /**
     * 프로젝트 단위 프리뷰의 준비(clone/install/build)는 비동기라, 그 도중 앱이 재시작되면 어느
     * 상태로도 가지 못한 PROVISIONING 행이 컨테이너를 붙든 채 남는다. 청소기가 이 행을 EXPIRED 로
     * 닫아버리면 사용자 화면에는 "프리뷰 없음"만 남아 왜 안 떴는지 알 수 없으므로, 사유가 남는
     * FAILED 로 닫는다.
     */
    @Test
    void cleanupFailsProvisioningSessionThatNeverFinished() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        DockerContainerService dockerService = mock(DockerContainerService.class);
        PreviewSessionService service = new PreviewSessionService(
                repository,
                dockerService,
                mock(TaskStore.class),
                properties(),
                gatewayUrlResolver(),
                accessCookies()
        );
        PreviewSessionEntity stuck = new PreviewSessionEntity(
                "session-2",
                "token-2",
                1L,
                11L,
                null,
                null,
                "container-2",
                32769,
                "https://preview.qeploy.test/session-2/",
                LocalDateTime.now().minusMinutes(1),
                PreviewSessionStatus.PROVISIONING
        );
        when(repository.findByStatusInAndExpiresAtBefore(any(), any(LocalDateTime.class)))
                .thenReturn(List.of(stuck));
        when(repository.save(stuck)).thenReturn(stuck);

        service.cleanupExpired();

        assertThat(stuck.getStatus()).isEqualTo(PreviewSessionStatus.FAILED.name());
        assertThat(stuck.getFailureReason()).contains("제한 시간");
        verify(dockerService).removeContainer("container-2");
    }

    // Issue #71 (High): after a CloudOps RESTART, the caller (InfraOpsAgentService) re-queries
    // Docker's newly assigned ephemeral host port and must be able to persist it here so
    // PreviewGatewayService's next proxy lookup reads the fresh port instead of the stale
    // pre-restart one — that's the actual fix; this pins the persistence half of it in isolation
    // from InfraOpsAgentService's own (mocked) regression test.
    @Test
    void updateHostPortPersistsNewPortAndReturnsRefreshedInfo() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository,
                mock(DockerContainerService.class),
                mock(TaskStore.class),
                properties(),
                gatewayUrlResolver(),
                accessCookies()
        );
        PreviewSessionEntity session = new PreviewSessionEntity(
                "session-1", "token", 1L, 11L, 21L, "task-1",
                "container-1", 32768, "https://preview.qeploy.test/session-1/",
                LocalDateTime.now().plusMinutes(30)
        );
        when(repository.findById("session-1")).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);

        PreviewSessionInfo result = service.updateHostPort("session-1", 40001);

        // The row mutates in place (rebindPort), and the returned info reflects the new port —
        // publicUrl is untouched (it never encoded the old port to begin with).
        assertThat(session.getHostPort()).isEqualTo(40001);
        assertThat(result.hostPort()).isEqualTo(40001);
        assertThat(result.publicUrl()).isEqualTo("https://preview.qeploy.test/session-1/");
        verify(repository).save(session);
    }

    @Test
    void updateHostPortThrowsWhenSessionMissing() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository,
                mock(DockerContainerService.class),
                mock(TaskStore.class),
                properties(),
                gatewayUrlResolver(),
                accessCookies()
        );
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateHostPort("missing", 40001))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void findActiveByProjectDelegatesToOwnerScopedFinder() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository,
                mock(DockerContainerService.class),
                mock(TaskStore.class),
                properties(),
                gatewayUrlResolver(),
                accessCookies()
        );
        PreviewSessionEntity active = new PreviewSessionEntity(
                "session-1", "token", 1L, 11L, 21L, "task-1",
                "container-1", 32768, "https://preview.qeploy.test/session-1/",
                LocalDateTime.now().plusMinutes(30)
        );
        when(repository.findFirstByProjectIdAndOwnerUserIdAndStatusOrderByLastAccessedAtDesc(
                11L, 1L, PreviewSessionStatus.ACTIVE.name()
        )).thenReturn(Optional.of(active));

        Optional<PreviewSessionInfo> result = service.findActiveByProject(11L, 1L);

        assertThat(result).isPresent();
        assertThat(result.get().containerId()).isEqualTo("container-1");
        verify(repository).findFirstByProjectIdAndOwnerUserIdAndStatusOrderByLastAccessedAtDesc(
                11L, 1L, PreviewSessionStatus.ACTIVE.name());
    }

    @Test
    void findActiveByProjectReturnsEmptyWhenNoActiveSessionForOwner() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository,
                mock(DockerContainerService.class),
                mock(TaskStore.class),
                properties(),
                gatewayUrlResolver(),
                accessCookies()
        );
        when(repository.findFirstByProjectIdAndOwnerUserIdAndStatusOrderByLastAccessedAtDesc(
                11L, 1L, PreviewSessionStatus.ACTIVE.name()
        )).thenReturn(Optional.empty());

        assertThat(service.findActiveByProject(11L, 1L)).isEmpty();
    }

    /** 게이트웨이 오리진 해석은 PreviewGatewayUrlResolverTest 가 따로 검증한다. */
    private PreviewGatewayUrlResolver gatewayUrlResolver() {
        return new PreviewGatewayUrlResolver(properties(), new CorsProperties(List.of(), List.of()));
    }

    private PreviewAccessCookies accessCookies() {
        return new PreviewAccessCookies(
                new JwtProperties("test-secret-key-that-is-long-enough-32", 3600000L, 7200000L));
    }

    // ── 저장소 연결 승인 유예 ──────────────────────────────────────────────────────────

    @Test
    void bindingApprovalHoldPushesTheExpiryOut() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository, mock(DockerContainerService.class), mock(TaskStore.class),
                properties(), gatewayUrlResolver(), accessCookies()
        );
        PreviewSessionEntity session = activeSessionExpiringIn(Duration.ofMinutes(30));
        when(repository.findByTaskIdAndStatus("task-1", PreviewSessionStatus.ACTIVE.name()))
                .thenReturn(Optional.of(session));
        when(repository.save(any(PreviewSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.holdForBindingApproval("task-1");

        assertThat(session.getExpiresAt()).isAfter(LocalDateTime.now().plusHours(5));
    }

    @Test
    void aPreviewAccessNeverPullsTheHoldBackIn() {
        // 유예를 걸어도 바로 뒤에 FE 가 프리뷰를 자동으로 띄우면 게이트웨이 접근이 일어난다.
        // touch 가 만료를 무조건 now+ttl 로 덮어쓰던 시절에는 그 한 번으로 유예가 통째로
        // 지워졌다 — 유예는 프리뷰를 한 번도 열지 않았을 때만 살아남았다(2026-08-18 운영 실측).
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository, mock(DockerContainerService.class), mock(TaskStore.class),
                properties(), gatewayUrlResolver(), accessCookies()
        );
        PreviewSessionEntity held = activeSessionExpiringIn(Duration.ofHours(6));
        when(repository.findByIdAndAccessTokenAndStatus(
                "session-1", "token-1", PreviewSessionStatus.ACTIVE.name()))
                .thenReturn(Optional.of(held));
        when(repository.save(any(PreviewSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.resolveGateway("session-1", "token-1");

        assertThat(held.getExpiresAt()).isAfter(LocalDateTime.now().plusHours(5));
    }

    private PreviewSessionEntity activeSessionExpiringIn(Duration remaining) {
        return new PreviewSessionEntity(
                "session-1", "token-1", 1L, 11L, 21L, "task-1", "container-1", 32768,
                "https://preview.qeploy.test/api/v1/previews/session-1/token-1/",
                LocalDateTime.now().plus(remaining),
                PreviewSessionStatus.ACTIVE
        );
    }

    private PreviewProperties properties() {
        PreviewProperties properties = new PreviewProperties();
        properties.setGatewayBaseUrl("https://preview.qeploy.test");
        properties.setTtl(Duration.ofMinutes(30));
        return properties;
    }

    private AgentTask task() {
        return new AgentTask(
                "task-1",
                1L,
                11L,
                21L,
                TaskStatus.RUNNING,
                null,
                null,
                null,
                null,
                Instant.now()
        );
    }

    /**
     * Issue #77 G4: 유출된 주소가 세션 수명 내내 유효하던 문제. 소유자가 프리뷰를 다시 열면
     * (= 접근을 발급받으면) 그 시점에 예전 주소가 죽어야 한다.
     */
    @Test
    void grantingAccessRotatesTheTokenSoThePreviousUrlStopsWorking() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository,
                mock(DockerContainerService.class),
                mock(TaskStore.class),
                properties(),
                gatewayUrlResolver(),
                accessCookies()
        );
        PreviewSessionEntity session = new PreviewSessionEntity(
                "session-1", "old-token", 7L, 11L, null, null, "container-1", 32768,
                "https://preview.qeploy.test/api/v1/previews/session-1/old-token/",
                LocalDateTime.now().plusMinutes(30));
        when(repository.findByIdAndOwnerUserId("session-1", 7L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);

        var grant = service.grantAccess("session-1", 7L, Duration.ofMinutes(30));

        assertThat(session.getAccessToken()).isNotEqualTo("old-token");
        assertThat(grant.previewUrl()).doesNotContain("old-token").contains(session.getAccessToken());
        assertThat(grant.cookiePath()).isEqualTo("/api/v1/previews/session-1/");
        assertThat(grant.cookieMaxAge()).isLessThanOrEqualTo(Duration.ofMinutes(30));
    }

    /** 남의 세션은 존재 자체를 알려주지 않는다 — 기존 close/status API 와 같은 계약. */
    @Test
    void refusesToGrantAccessToAForeignSession() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository,
                mock(DockerContainerService.class),
                mock(TaskStore.class),
                properties(),
                gatewayUrlResolver(),
                accessCookies()
        );
        when(repository.findByIdAndOwnerUserId("session-1", 8L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantAccess("session-1", 8L, Duration.ofMinutes(30)))
                .isInstanceOf(NotFoundException.class);
    }
}
