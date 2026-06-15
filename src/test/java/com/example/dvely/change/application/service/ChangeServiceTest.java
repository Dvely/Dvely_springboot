package com.example.dvely.change.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.change.infrastructure.persistence.entity.ChangeEntity;
import com.example.dvely.change.infrastructure.persistence.repository.SpringDataChangeRepository;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChangeServiceTest {

    @Test
    void recordsTaskScopedPreviewDiff() {
        SpringDataChangeRepository repository = mock(SpringDataChangeRepository.class);
        TaskStore taskStore = mock(TaskStore.class);
        PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
        DockerContainerService dockerService = mock(DockerContainerService.class);
        ChangeService service = new ChangeService(
                repository,
                taskStore,
                previewSessionService,
                dockerService,
                mock(ProjectRepository.class)
        );
        when(taskStore.get("task-1")).thenReturn(new AgentTask(
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
        ));
        when(previewSessionService.findByTaskId("task-1")).thenReturn(Optional.of(
                new PreviewSessionInfo(
                        "preview-1",
                        1L,
                        11L,
                        21L,
                        "task-1",
                        "container-1",
                        32768,
                        "https://preview.qeploy.test/preview-1/",
                        LocalDateTime.now().plusMinutes(30)
                )
        ));
        when(repository.findByTaskId("task-1")).thenReturn(Optional.empty());
        when(dockerService.exec(
                "container-1",
                "cd /workspace/app && (git add -N . >/dev/null 2>&1 || true) && git diff --no-ext-diff -- ."
        )).thenReturn("diff --git a/src/App.jsx b/src/App.jsx");

        service.record("task-1", "FAQ 추가");

        ArgumentCaptor<ChangeEntity> captor = ArgumentCaptor.forClass(ChangeEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTaskId()).isEqualTo("task-1");
        assertThat(captor.getValue().getPreviewSessionId()).isEqualTo("preview-1");
        assertThat(captor.getValue().getDiffText()).contains("src/App.jsx");
    }
}
