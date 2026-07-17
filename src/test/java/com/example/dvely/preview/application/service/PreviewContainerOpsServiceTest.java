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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
