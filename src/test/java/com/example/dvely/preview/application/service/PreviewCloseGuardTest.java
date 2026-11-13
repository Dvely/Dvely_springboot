package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.auth.infrastructure.config.JwtProperties;
import com.example.dvely.config.CorsProperties;
import com.example.dvely.preview.application.result.BulkPreviewCloseResult;
import com.example.dvely.preview.application.result.InUsePreview;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.config.PreviewGatewayUrlResolver;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import com.example.dvely.preview.infrastructure.security.PreviewAccessCookies;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 사람이 프리뷰를 닫는 경로가 작업 상태를 본다(#650). 진행 중이거나 결정을 기다리는 작업의 컨테이너에는 그 작업물의 유일한
 * 사본이 있다 — 닫으면 되살릴 수 없으므로, 거부하고 컨테이너 제거를 한 번도 부르지 않아야 한다.
 */
class PreviewCloseGuardTest {

    private static final Long OWNER = 7L;

    /** 이슈에 적은 잠긴 상태 일곱 개. 규칙이 "닫을 수 있는 셋 말고 전부" 라서, 상태가 늘면 여기와 어긋나 알려 준다. */
    private static final Set<TaskStatus> LOCKED = EnumSet.of(
            TaskStatus.PENDING, TaskStatus.WAITING_APPROVAL, TaskStatus.QUEUED, TaskStatus.RETRY_WAIT,
            TaskStatus.RUNNING, TaskStatus.WAITING_INPUT, TaskStatus.WAITING_RESULT_APPROVAL);

    private final SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
    private final DockerContainerService dockerService = mock(DockerContainerService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final PreviewSessionService service = new PreviewSessionService(
            repository, dockerService, taskStore, properties(), gatewayUrlResolver(), accessCookies(),
            mock(PreviewRuntimeConfigService.class), mock(PreviewCapacity.class));

    // ── 규칙 ────────────────────────────────────────────────────────────────────────────

    @Test
    void theOwnerMayCloseExactlyTheFinishedAndFailedTasksPreviews() {
        Set<TaskStatus> locked = Arrays.stream(TaskStatus.values())
                .filter(status -> PreviewClosePolicy.blockingOwnerClose(
                        session("s", "t", PreviewSessionStatus.ACTIVE), id -> task(id, status)).isPresent())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TaskStatus.class)));

        assertThat(locked).isEqualTo(LOCKED);
    }

    @Test
    void automaticReclaimDiffersFromTheOwnerOnlyByFailed() {
        // 빌드 실패 복구의 재시도가 같은 컨테이너의 작업물을 이어 쓴다 — 저절로는 닫지 않고, 사람은 닫을 수 있다.
        Set<TaskStatus> ownerOnly = EnumSet.copyOf(PreviewClosePolicy.CLOSABLE_BY_OWNER);
        ownerOnly.removeAll(PreviewClosePolicy.RECLAIMABLE);

        assertThat(ownerOnly).containsExactly(TaskStatus.FAILED);
        assertThat(PreviewClosePolicy.reclaimable(task("t", TaskStatus.FAILED))).isFalse();
        assertThat(PreviewClosePolicy.reclaimable(task("t", TaskStatus.DONE))).isTrue();
        assertThat(PreviewClosePolicy.reclaimable(null)).isTrue();
    }

    @Test
    void aProjectPreviewOrASessionWithoutARunIsNeverBlocked() {
        assertThat(PreviewClosePolicy.blockingOwnerClose(session("s", null, PreviewSessionStatus.ACTIVE),
                id -> { throw new AssertionError("프로젝트 프리뷰는 작업을 읽지 않는다"); })).isEmpty();
        assertThat(PreviewClosePolicy.blockingOwnerClose(session("s", "gone", PreviewSessionStatus.ACTIVE), id -> null))
                .isEmpty();
    }

    // ── DELETE /preview-sessions/{id} ──────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"PENDING", "WAITING_APPROVAL", "QUEUED", "RETRY_WAIT", "RUNNING",
            "WAITING_INPUT", "WAITING_RESULT_APPROVAL"})
    void closingOneRefusesWhileItsTaskIsInUse(TaskStatus status) {
        PreviewSessionEntity session = owned(session("s1", "task-1", PreviewSessionStatus.ACTIVE));
        running("task-1", status);

        assertThatThrownBy(() -> service.closeOwned("s1", OWNER))
                .isInstanceOfSatisfying(PreviewInUseException.class, e -> assertThat(e.blocked())
                        .containsExactly(new InUsePreview("s1", 11L, "task-1", status)));

        assertThat(session.getStatus()).isEqualTo(PreviewSessionStatus.ACTIVE.name());
        verify(dockerService, never()).removeContainer(anyString());
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"DONE", "CANCELLED", "FAILED"})
    void closingOneWorksOnceItsTaskIsOverOrFailed(TaskStatus status) {
        PreviewSessionEntity session = owned(session("s1", "task-1", PreviewSessionStatus.ACTIVE));
        running("task-1", status);

        assertThat(service.closeOwned("s1", OWNER)).isTrue();

        assertThat(session.getStatus()).isEqualTo(PreviewSessionStatus.CLOSED.name());
        verify(dockerService).removeContainer("container-s1");
    }

    @Test
    void closingOneProjectPreviewWorksAsBefore() {
        PreviewSessionEntity session = owned(session("s1", null, PreviewSessionStatus.ACTIVE));

        assertThat(service.closeOwned("s1", OWNER)).isTrue();

        assertThat(session.getStatus()).isEqualTo(PreviewSessionStatus.CLOSED.name());
    }

    @Test
    void aTaskSessionStillBeingPreparedIsRefusedTooButAPreparingProjectPreviewStaysNotFound() {
        owned(session("s1", "task-1", PreviewSessionStatus.PROVISIONING));
        running("task-1", TaskStatus.RUNNING);
        assertThatThrownBy(() -> service.closeOwned("s1", OWNER)).isInstanceOf(PreviewInUseException.class);

        // 준비 중인 프로젝트 프리뷰의 취소는 이 경로가 다루지 않는다(지금처럼 404)
        owned(session("s2", null, PreviewSessionStatus.PROVISIONING));
        assertThat(service.closeOwned("s2", OWNER)).isFalse();
        verify(dockerService, never()).removeContainer(anyString());
    }

    @Test
    void theRefusalSaysWhatToDoNext() {
        assertThat(new PreviewInUseException(List.of(inUse(TaskStatus.WAITING_RESULT_APPROVAL))).getMessage())
                .contains("결과 승인을 먼저 결정해 주세요")
                .doesNotContain("취소한 뒤");
        assertThat(new PreviewInUseException(List.of(inUse(TaskStatus.RUNNING))).getMessage())
                .contains("작업이 끝나거나 작업을 취소한 뒤 닫을 수 있습니다")
                .doesNotContain("결과 승인");
        assertThat(new PreviewInUseException(List.of(inUse(TaskStatus.WAITING_RESULT_APPROVAL), inUse(TaskStatus.QUEUED)))
                .getMessage())
                .contains("결과 승인을 먼저 결정해 주세요", "취소한 뒤");
    }

    // ── DELETE /agent/session ──────────────────────────────────────────────────────────

    @Test
    void closingAllKeepsThePreviewsInUseAndClosesTheRest() {
        PreviewSessionEntity waiting = session("waiting", "task-w", PreviewSessionStatus.ACTIVE);
        PreviewSessionEntity done = session("done", "task-d", PreviewSessionStatus.ACTIVE);
        PreviewSessionEntity project = session("project", null, PreviewSessionStatus.ACTIVE);
        liveSessions(waiting, done, project);
        running("task-w", TaskStatus.WAITING_RESULT_APPROVAL);
        running("task-d", TaskStatus.DONE);

        BulkPreviewCloseResult result = service.closeAllOwned(OWNER);

        assertThat(result.closed()).isEqualTo(2);
        assertThat(result.kept()).containsExactly(
                new InUsePreview("waiting", 11L, "task-w", TaskStatus.WAITING_RESULT_APPROVAL));
        assertThat(waiting.getStatus()).isEqualTo(PreviewSessionStatus.ACTIVE.name());
        verify(dockerService, never()).removeContainer("container-waiting");
        verify(dockerService).removeContainer("container-done");
        verify(dockerService).removeContainer("container-project");
    }

    @Test
    void closingAllRefusesWhenEveryOpenPreviewIsInUse() {
        liveSessions(session("a", "task-a", PreviewSessionStatus.ACTIVE), session("b", "task-b", PreviewSessionStatus.ACTIVE));
        running("task-a", TaskStatus.RUNNING);
        running("task-b", TaskStatus.WAITING_INPUT);

        assertThatThrownBy(() -> service.closeAllOwned(OWNER))
                .isInstanceOfSatisfying(PreviewInUseException.class,
                        e -> assertThat(e.blocked()).extracting(InUsePreview::sessionId).containsExactly("a", "b"));
        verify(dockerService, never()).removeContainer(anyString());
    }

    @Test
    void closingAllWithNothingOpenClosesNothing() {
        liveSessions();

        assertThat(service.closeAllOwned(OWNER)).isEqualTo(new BulkPreviewCloseResult(0, List.of()));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────────────────

    private PreviewSessionEntity owned(PreviewSessionEntity session) {
        when(repository.findByIdAndOwnerUserId(session.getId(), OWNER)).thenReturn(Optional.of(session));
        return session;
    }

    private void liveSessions(PreviewSessionEntity... sessions) {
        when(repository.findByOwnerUserIdAndStatus(OWNER, PreviewSessionStatus.ACTIVE.name()))
                .thenReturn(List.of(sessions));
    }

    private void running(String taskId, TaskStatus status) {
        when(taskStore.get(taskId)).thenReturn(task(taskId, status));
    }

    private static AgentTask task(String taskId, TaskStatus status) {
        return new AgentTask(taskId, OWNER, 11L, 21L, status, null, null, null, null, Instant.now());
    }

    private static InUsePreview inUse(TaskStatus status) {
        return new InUsePreview("s", 11L, "t", status);
    }

    private static PreviewSessionEntity session(String id, String taskId, PreviewSessionStatus status) {
        return new PreviewSessionEntity(id, "token-" + id, OWNER, 11L, null, taskId, "container-" + id,
                "172.18.0.2", "http://preview/" + id, LocalDateTime.now().plusMinutes(30), status);
    }

    private static PreviewProperties properties() {
        PreviewProperties properties = new PreviewProperties();
        properties.setGatewayBaseUrl("https://preview.qeploy.test");
        properties.setTtl(Duration.ofMinutes(30));
        return properties;
    }

    private static PreviewGatewayUrlResolver gatewayUrlResolver() {
        return new PreviewGatewayUrlResolver(properties(), new CorsProperties(List.of(), List.of()));
    }

    private static PreviewAccessCookies accessCookies() {
        return new PreviewAccessCookies(new JwtProperties("test-secret-key-that-is-long-enough-32", 3600000L, 7200000L));
    }
}
