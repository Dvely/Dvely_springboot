package com.example.dvely.webhook.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WebhookServiceTest {

    @Test
    void workflowRun_completesOnlyExactRunAndHeadSha() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        DeploymentHistoryRepository historyRepository = mock(DeploymentHistoryRepository.class);
        ChangeService changeService = mock(ChangeService.class);
        WebhookService service = new WebhookService(
                mock(GithubProperties.class),
                projectRepository,
                historyRepository,
                changeService
        );
        DeploymentHistory history = history("workflow-sha");
        Project project = project();
        when(historyRepository.findByWorkflowRunId(901L)).thenReturn(Optional.of(history));
        when(historyRepository.findLatestByProjectId(11L)).thenReturn(Optional.of(history));
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));

        service.handleEvent("workflow_run", payload("workflow-sha"));

        assertThat(history.getStatus()).isEqualTo(DeployStatus.LIVE);
        assertThat(project.getDeployStatus()).isEqualTo(DeployStatus.LIVE);
        verify(historyRepository).save(history);
        verify(projectRepository).save(project);
        verify(changeService).markDeployed("task-51");
    }

    @Test
    void workflowRun_ignoresMismatchedHeadSha() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        DeploymentHistoryRepository historyRepository = mock(DeploymentHistoryRepository.class);
        ChangeService changeService = mock(ChangeService.class);
        WebhookService service = new WebhookService(
                mock(GithubProperties.class),
                projectRepository,
                historyRepository,
                changeService
        );
        DeploymentHistory history = history("expected-sha");
        when(historyRepository.findByWorkflowRunId(901L)).thenReturn(Optional.of(history));
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project()));

        service.handleEvent("workflow_run", payload("different-sha"));

        assertThat(history.getStatus()).isEqualTo(DeployStatus.IN_PROGRESS);
        verify(historyRepository, never()).save(history);
        verify(changeService, never()).markDeployed("task-51");
    }

    @Test
    void workflowRun_doesNotOverwriteProjectWithOlderDeploymentCompletion() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        DeploymentHistoryRepository historyRepository = mock(DeploymentHistoryRepository.class);
        ChangeService changeService = mock(ChangeService.class);
        WebhookService service = new WebhookService(
                mock(GithubProperties.class),
                projectRepository,
                historyRepository,
                changeService
        );
        DeploymentHistory history = history("workflow-sha");
        DeploymentHistory latestHistory = mock(DeploymentHistory.class);
        when(latestHistory.getId()).thenReturn(52L);
        Project project = project();
        when(historyRepository.findByWorkflowRunId(901L)).thenReturn(Optional.of(history));
        when(historyRepository.findLatestByProjectId(11L)).thenReturn(Optional.of(latestHistory));
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));

        service.handleEvent("workflow_run", payload("workflow-sha"));

        assertThat(history.getStatus()).isEqualTo(DeployStatus.LIVE);
        assertThat(project.getDeployStatus()).isEqualTo(DeployStatus.IN_PROGRESS);
        verify(historyRepository).save(history);
        verify(projectRepository, never()).save(project);
        verify(changeService).markDeployed("task-51");
    }

    private byte[] payload(String headSha) {
        return ("""
                {
                  "repository": {"full_name": "octo/repo"},
                  "workflow_run": {
                    "id": 901,
                    "name": "Qeploy Deploy to GitHub Pages",
                    "display_title": "Qeploy deployment correlation-51",
                    "status": "completed",
                    "conclusion": "success",
                    "head_sha": "%s"
                  }
                }
                """.formatted(headSha)).getBytes(StandardCharsets.UTF_8);
    }

    private DeploymentHistory history(String workflowHeadSha) {
        LocalDateTime now = LocalDateTime.now();
        return new DeploymentHistory(
                51L,
                1L,
                11L,
                DeployTargetType.LATEST,
                "v7",
                "https://octo.github.io/repo/",
                DeployStatus.IN_PROGRESS,
                901L,
                "correlation-51",
                "release-sha",
                workflowHeadSha,
                "Release title",
                "Release description",
                "octo",
                "https://avatars.example/octo",
                17,
                now.minusMinutes(5),
                "task-51",
                null,
                1,
                3,
                null,
                null,
                null,
                now,
                now
        );
    }

    private Project project() {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L,
                1L,
                "my-project",
                ProjectStatus.ACTIVE,
                "vue",
                null,
                "fast",
                DeployStatus.IN_PROGRESS,
                "https://octo.github.io/repo/",
                "v7",
                "octo/repo",
                "octo/repo",
                RepositoryVisibility.PUBLIC,
                RepositoryBindingStatus.BOUND,
                RepositoryHealthStatus.HEALTHY,
                false,
                now,
                now
        );
    }
}
