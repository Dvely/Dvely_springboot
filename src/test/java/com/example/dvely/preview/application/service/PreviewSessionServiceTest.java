package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PreviewSessionServiceTest {

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
                properties
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
                properties()
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
        when(repository.findByStatusAndExpiresAtBefore(
                org.mockito.ArgumentMatchers.eq(PreviewSessionStatus.ACTIVE.name()),
                any(LocalDateTime.class)
        )).thenReturn(List.of(expired));
        when(repository.save(expired)).thenReturn(expired);

        service.cleanupExpired();

        assertThat(expired.getStatus()).isEqualTo(PreviewSessionStatus.EXPIRED.name());
        verify(dockerService).removeContainer("container-1");
    }

    @Test
    void findActiveByProjectDelegatesToOwnerScopedFinder() {
        SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
        PreviewSessionService service = new PreviewSessionService(
                repository,
                mock(DockerContainerService.class),
                mock(TaskStore.class),
                properties()
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
                properties()
        );
        when(repository.findFirstByProjectIdAndOwnerUserIdAndStatusOrderByLastAccessedAtDesc(
                11L, 1L, PreviewSessionStatus.ACTIVE.name()
        )).thenReturn(Optional.empty());

        assertThat(service.findActiveByProject(11L, 1L)).isEmpty();
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
}
