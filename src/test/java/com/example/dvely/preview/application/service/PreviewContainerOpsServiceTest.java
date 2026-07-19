package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.ContainerRuntimeStatus;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.result.PreviewContainerStatusResult;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

class PreviewContainerOpsServiceTest {

    private final SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
    private final DockerContainerService dockerService = mock(DockerContainerService.class);
    private final PreviewContainerOpsService service = new PreviewContainerOpsService(repository, dockerService);

    @Test
    void getStatusRejectsForeignOwnerSession() {
        when(repository.findByIdAndOwnerUserId("session-1", 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatus(2L, "session-1"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("session-1");
    }

    @Test
    void getLogsRejectsForeignOwnerSession() {
        when(repository.findByIdAndOwnerUserId("session-1", 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLogs(2L, "session-1", null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("session-1");
    }

    @Test
    void getStatusReturnsNoResourcesForClosedSessionWithoutQueryingStats() {
        PreviewSessionEntity session = session(PreviewSessionStatus.CLOSED);
        when(repository.findByIdAndOwnerUserId("session-1", 1L)).thenReturn(Optional.of(session));
        when(dockerService.getContainerStatus("container-1")).thenReturn(ContainerRuntimeStatus.notFound());

        PreviewContainerStatusResult result = service.getStatus(1L, "session-1");

        assertThat(result.containerRunning()).isFalse();
        assertThat(result.resources()).isNull();
        verify(dockerService, never()).getContainerStats(any());
    }

    @Test
    void getStatusStaysOn200PathWithNullResourcesWhenStatsUnavailable() {
        PreviewSessionEntity session = session(PreviewSessionStatus.ACTIVE);
        when(repository.findByIdAndOwnerUserId("session-1", 1L)).thenReturn(Optional.of(session));
        when(dockerService.getContainerStatus("container-1"))
                .thenReturn(new ContainerRuntimeStatus(true, false, null, LocalDateTime.now()));
        when(dockerService.getContainerStats("container-1")).thenReturn(Optional.empty());

        PreviewContainerStatusResult result = service.getStatus(1L, "session-1");

        assertThat(result.containerRunning()).isTrue();
        assertThat(result.resources()).isNull();
    }

    @Test
    void getLogsClampsTailBelowMinimumToOne() {
        PreviewSessionEntity session = session(PreviewSessionStatus.ACTIVE);
        when(repository.findByIdAndOwnerUserId("session-1", 1L)).thenReturn(Optional.of(session));
        when(dockerService.isContainerRunning("container-1")).thenReturn(true);
        when(dockerService.getContainerLogs(eq("container-1"), eq(1), isNull())).thenReturn("log");

        service.getLogs(1L, "session-1", 0, null);

        verify(dockerService).getContainerLogs("container-1", 1, null);
    }

    @Test
    void getLogsClampsTailAboveMaximumToTwoThousand() {
        PreviewSessionEntity session = session(PreviewSessionStatus.ACTIVE);
        when(repository.findByIdAndOwnerUserId("session-1", 1L)).thenReturn(Optional.of(session));
        when(dockerService.isContainerRunning("container-1")).thenReturn(true);
        when(dockerService.getContainerLogs(eq("container-1"), eq(2000), isNull())).thenReturn("log");

        service.getLogs(1L, "session-1", 5000, null);

        verify(dockerService).getContainerLogs("container-1", 2000, null);
    }

    @Test
    void getLogsDefaultsTailToTwoHundredWhenOmitted() {
        PreviewSessionEntity session = session(PreviewSessionStatus.ACTIVE);
        when(repository.findByIdAndOwnerUserId("session-1", 1L)).thenReturn(Optional.of(session));
        when(dockerService.isContainerRunning("container-1")).thenReturn(true);
        when(dockerService.getContainerLogs(eq("container-1"), eq(200), isNull())).thenReturn("log");

        service.getLogs(1L, "session-1", null, null);

        verify(dockerService).getContainerLogs("container-1", 200, null);
    }

    @Test
    void getLogsConvertsSinceSecondsToAbsoluteEpochSeconds() {
        PreviewSessionEntity session = session(PreviewSessionStatus.ACTIVE);
        when(repository.findByIdAndOwnerUserId("session-1", 1L)).thenReturn(Optional.of(session));
        when(dockerService.isContainerRunning("container-1")).thenReturn(true);
        when(dockerService.getContainerLogs(eq("container-1"), eq(200), any())).thenReturn("log");

        long before = Instant.now().getEpochSecond();
        service.getLogs(1L, "session-1", null, 60);
        long after = Instant.now().getEpochSecond();

        ArgumentCaptor<Integer> sinceCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(dockerService).getContainerLogs(eq("container-1"), eq(200), sinceCaptor.capture());
        int since = sinceCaptor.getValue();
        assertThat(since).isBetween((int) (before - 60), (int) (after - 60));
    }

    // review F2: @Transactional on getStatus/getLogs would hold a pooled DB connection for the
    // whole Docker I/O span (stats ~1-3s, logs up to 10s) — regression-guard against re-adding
    // it, since a small Hikari pool could otherwise be exhausted by a handful of concurrent slow
    // Docker calls. Reflection (not a live pool-exhaustion simulation) is the deterministic way
    // to pin this: it fails immediately and explicitly if either method is re-annotated.
    @Test
    void getStatusAndGetLogsAreNotTransactionalToAvoidHoldingConnectionsDuringDockerIO() throws NoSuchMethodException {
        Method getStatus = PreviewContainerOpsService.class.getMethod("getStatus", Long.class, String.class);
        Method getLogs = PreviewContainerOpsService.class
                .getMethod("getLogs", Long.class, String.class, Integer.class, Integer.class);

        assertThat(getStatus.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(getLogs.isAnnotationPresent(Transactional.class)).isFalse();
    }

    // review F8: "recent N seconds" can't be negative — clamped to 0 (now) rather than producing
    // a since-cutoff in the future, mirroring the tail clamp policy.
    @Test
    void getLogsClampsNegativeSinceSecondsToZero() {
        PreviewSessionEntity session = session(PreviewSessionStatus.ACTIVE);
        when(repository.findByIdAndOwnerUserId("session-1", 1L)).thenReturn(Optional.of(session));
        when(dockerService.isContainerRunning("container-1")).thenReturn(true);
        when(dockerService.getContainerLogs(eq("container-1"), eq(200), any())).thenReturn("log");

        long before = Instant.now().getEpochSecond();
        service.getLogs(1L, "session-1", null, -100);
        long after = Instant.now().getEpochSecond();

        ArgumentCaptor<Integer> sinceCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(dockerService).getContainerLogs(eq("container-1"), eq(200), sinceCaptor.capture());
        // sinceSeconds clamped to 0 => cutoff == "now", not 100s in the future.
        assertThat(sinceCaptor.getValue()).isBetween((int) before, (int) after);
    }

    // review F8: the epoch subtraction must happen in long arithmetic and clamp into int range
    // before narrowing, instead of an unchecked `(int)` cast that could silently wrap around.
    // Not reachable through getLogs() with today's epoch value (nowhere near the int boundary
    // until ~2038), so this exercises the extracted pure clamp function directly with a
    // fabricated "now" — the only deterministic way to hit the boundary without mocking time.
    @Test
    void clampToEpochSecondsClampsIntoIntRangeInsteadOfOverflowing() {
        long farFutureNow = (long) Integer.MAX_VALUE + 1_000_000_000L;

        int result = PreviewContainerOpsService.clampToEpochSeconds(farFutureNow, 0);

        assertThat(result).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void clampToEpochSecondsComputesNormalCutoffWithinIntRange() {
        int result = PreviewContainerOpsService.clampToEpochSeconds(2_000_000_000L, 60);

        assertThat(result).isEqualTo(1_999_999_940);
    }

    private PreviewSessionEntity session(PreviewSessionStatus status) {
        PreviewSessionEntity entity = new PreviewSessionEntity(
                "session-1",
                "token",
                1L,
                11L,
                21L,
                "task-1",
                "container-1",
                32768,
                "https://preview.qeploy.test/session-1/",
                LocalDateTime.now().plusMinutes(30)
        );
        if (status != PreviewSessionStatus.ACTIVE) {
            entity.close(status);
        }
        return entity;
    }
}
