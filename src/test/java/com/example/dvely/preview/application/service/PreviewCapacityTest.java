package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.common.limit.UserLimitExceededException;
import com.example.dvely.common.limit.UserLimitProperties;
import com.example.dvely.common.response.ErrorCode;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreviewCapacityTest {

    private static final Long USER_ID = 1L;

    private final SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
    private final DockerContainerService dockerService = mock(DockerContainerService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final PreviewCapacity capacity =
            new PreviewCapacity(repository, dockerService, taskStore, new UserLimitProperties(2, 3));

    @Test
    void doesNothingWhileThereIsRoom() {
        live(session("a", "task-a", PreviewSessionStatus.ACTIVE), session("b", null, PreviewSessionStatus.ACTIVE));

        capacity.makeRoom(USER_ID);

        verify(dockerService, never()).removeContainer(anyString());
    }

    @Test
    void closesTheLeastRecentlyViewedFinishedTaskPreviewFirst() {
        // 오래 안 본 순서로 읽힌다. 가장 오래된 끝난 작업 하나만 닫으면 자리가 난다 — 더 최근 것은 남긴다.
        PreviewSessionEntity oldDone = session("old", "task-old", PreviewSessionStatus.ACTIVE);
        PreviewSessionEntity project = session("project", null, PreviewSessionStatus.ACTIVE);
        PreviewSessionEntity newDone = session("new", "task-new", PreviewSessionStatus.ACTIVE);
        live(oldDone, project, newDone);
        task("task-old", TaskStatus.DONE);
        task("task-new", TaskStatus.DONE);

        capacity.makeRoom(USER_ID);

        verify(dockerService).removeContainer("container-old");
        assertThat(oldDone.getStatus()).isEqualTo(PreviewSessionStatus.EXPIRED.name());
        verify(dockerService, never()).removeContainer("container-new");
        assertThat(newDone.getStatus()).isEqualTo(PreviewSessionStatus.ACTIVE.name());
    }

    @Test
    void neverClosesWhatSomeoneIsStillUsingOrWaitingOn() {
        // 프로젝트 프리뷰(사용자가 직접 연 것), 결과 승인 대기(그 프리뷰가 승인 대상), 실패(재시도가 작업물을 이어 씀),
        // 진행 중, 준비 중 — 전부 남기고 거절한다.
        live(
                session("project", null, PreviewSessionStatus.ACTIVE),
                session("waiting", "task-waiting", PreviewSessionStatus.ACTIVE),
                session("failed", "task-failed", PreviewSessionStatus.ACTIVE),
                session("running", "task-running", PreviewSessionStatus.ACTIVE),
                session("provisioning", "task-provisioning", PreviewSessionStatus.PROVISIONING)
        );
        task("task-waiting", TaskStatus.WAITING_RESULT_APPROVAL);
        task("task-failed", TaskStatus.FAILED);
        task("task-running", TaskStatus.RUNNING);
        task("task-provisioning", TaskStatus.DONE);

        assertThatThrownBy(() -> capacity.makeRoom(USER_ID))
                .isInstanceOfSatisfying(UserLimitExceededException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.PREVIEW_LIMIT_EXCEEDED);
                    assertThat(exception.getMessage()).contains("5개").contains("3개");
                });
        verify(dockerService, never()).removeContainer(anyString());
    }

    @Test
    void closesAsManyFinishedPreviewsAsNeededAndCancelledCountsAsFinished() {
        PreviewSessionEntity cancelled = session("cancelled", "task-cancelled", PreviewSessionStatus.ACTIVE);
        PreviewSessionEntity done = session("done", "task-done", PreviewSessionStatus.ACTIVE);
        live(cancelled, session("project", null, PreviewSessionStatus.ACTIVE), done,
                session("waiting", "task-waiting", PreviewSessionStatus.ACTIVE));
        task("task-cancelled", TaskStatus.CANCELLED);
        task("task-done", TaskStatus.DONE);
        task("task-waiting", TaskStatus.WAITING_RESULT_APPROVAL);

        assertThatCode(() -> capacity.makeRoom(USER_ID)).doesNotThrowAnyException();

        verify(dockerService).removeContainer("container-cancelled");
        verify(dockerService).removeContainer("container-done");
    }

    @Test
    void confirmKeepsTheNewSessionWhenTheCountIsWithinTheLimit() {
        PreviewSessionEntity created = session("created", "task-1", PreviewSessionStatus.PROVISIONING);
        when(repository.countByOwnerUserIdAndStatusIn(eq(USER_ID), any())).thenReturn(3L);

        capacity.confirm(USER_ID, created);

        verify(dockerService, never()).removeContainer(anyString());
        assertThat(created.getStatus()).isEqualTo(PreviewSessionStatus.PROVISIONING.name());
    }

    @Test
    void confirmBacksOutTheNewSessionWhenAConcurrentRequestFilledTheLastSlot() {
        PreviewSessionEntity created = session("created", "task-1", PreviewSessionStatus.PROVISIONING);
        when(repository.countByOwnerUserIdAndStatusIn(eq(USER_ID), any())).thenReturn(4L);

        assertThatThrownBy(() -> capacity.confirm(USER_ID, created))
                .isInstanceOf(UserLimitExceededException.class);

        verify(dockerService).removeContainer("container-created");
        assertThat(created.getStatus()).isEqualTo(PreviewSessionStatus.CLOSED.name());
        verify(repository).save(created);
    }

    private void live(PreviewSessionEntity... sessions) {
        when(repository.findByOwnerUserIdAndStatusInOrderByLastAccessedAtAsc(eq(USER_ID), any()))
                .thenReturn(List.of(sessions));
    }

    private void task(String taskId, TaskStatus status) {
        when(taskStore.get(taskId)).thenReturn(
                new AgentTask(taskId, USER_ID, 11L, 21L, status, null, null, null, null, Instant.now()));
    }

    private static PreviewSessionEntity session(String id, String taskId, PreviewSessionStatus status) {
        return new PreviewSessionEntity(id, "token-" + id, USER_ID, 11L, null, taskId, "container-" + id,
                "172.18.0.2", "http://preview/" + id, LocalDateTime.now().plusMinutes(30), status);
    }
}
