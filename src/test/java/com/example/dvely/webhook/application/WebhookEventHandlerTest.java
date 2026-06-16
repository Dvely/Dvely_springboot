package com.example.dvely.webhook.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookEventHandlerTest {

    private ProjectRepository projectRepository;
    private UserRepository userRepository;
    private DeploymentHistoryRepository historyRepository;
    private ChangeService changeService;
    private WebhookEventHandler handler;
    private LocalDateTime receivedAt;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        userRepository = mock(UserRepository.class);
        historyRepository = mock(DeploymentHistoryRepository.class);
        changeService = mock(ChangeService.class);
        handler = new WebhookEventHandler(
                new ObjectMapper(),
                projectRepository,
                userRepository,
                historyRepository,
                changeService
        );
        receivedAt = LocalDateTime.of(2026, 6, 14, 20, 0);
    }

    @Test
    void workflowRunCompletesOnlyExactRunAndHeadSha() {
        DeploymentHistory history = history("workflow-sha");
        Project project = project();
        when(historyRepository.findByWorkflowRunId(901L)).thenReturn(Optional.of(history));
        when(historyRepository.findLatestByProjectId(11L)).thenReturn(Optional.of(history));
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));

        handler.handle("workflow_run", workflowPayload("workflow-sha"), receivedAt);

        assertThat(history.getStatus()).isEqualTo(DeployStatus.LIVE);
        assertThat(project.getDeployStatus()).isEqualTo(DeployStatus.LIVE);
        verify(historyRepository).save(history);
        verify(projectRepository).save(project);
        verify(changeService).markDeployed("task-51");
    }

    @Test
    void workflowRunIgnoresMismatchedHeadSha() {
        DeploymentHistory history = history("expected-sha");
        when(historyRepository.findByWorkflowRunId(901L)).thenReturn(Optional.of(history));
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project()));

        handler.handle("workflow_run", workflowPayload("different-sha"), receivedAt);

        assertThat(history.getStatus()).isEqualTo(DeployStatus.IN_PROGRESS);
        verify(historyRepository, never()).save(history);
        verify(changeService, never()).markDeployed("task-51");
    }

    @Test
    void defaultBranchPushSynchronizesHealthAndHeadCommit() {
        Project project = project();
        when(projectRepository.findAllBySourceRepository("octo/repo")).thenReturn(List.of(project));

        handler.handle("push", bytes("""
                {
                  "ref": "refs/heads/main",
                  "after": "push-sha",
                  "deleted": false,
                  "repository": {
                    "full_name": "octo/repo",
                    "default_branch": "main"
                  },
                  "head_commit": {
                    "message": "feat: webhook sync",
                    "timestamp": "2026-06-14T19:59:00+09:00",
                    "author": {"username": "octo", "name": "Octo Cat"}
                  },
                  "pusher": {"name": "octo"}
                }
                """), receivedAt);

        assertThat(project.getRepositoryHealthStatus()).isEqualTo(RepositoryHealthStatus.HEALTHY);
        assertThat(project.getRepositoryHeadSha()).isEqualTo("push-sha");
        assertThat(project.getRepositoryHeadMessage()).isEqualTo("feat: webhook sync");
        assertThat(project.getRepositoryHeadAuthor()).isEqualTo("octo");
        assertThat(project.getRepositoryHeadSyncedAt()).isEqualTo(receivedAt);
        verify(projectRepository).save(project);
    }

    @Test
    void versionTagPushSynchronizesRepositoryVersion() {
        Project project = project();
        when(projectRepository.findAllBySourceRepository("octo/repo")).thenReturn(List.of(project));

        handler.handle("push", bytes("""
                {
                  "ref": "refs/tags/v8",
                  "after": "tagged-sha",
                  "deleted": false,
                  "repository": {
                    "full_name": "octo/repo",
                    "default_branch": "main"
                  }
                }
                """), receivedAt);

        assertThat(project.getRepositoryVersion()).isEqualTo("v8");
        assertThat(project.getRepositoryVersionSyncedAt()).isEqualTo(receivedAt);
        verify(projectRepository).save(project);
    }

    @Test
    void delayedLowerVersionTagDoesNotDowngradeRepositoryVersion() {
        Project project = project();
        when(projectRepository.findAllBySourceRepository("octo/repo")).thenReturn(List.of(project));

        handler.handle("push", versionPayload("v9"), receivedAt);
        handler.handle("push", versionPayload("v8"), receivedAt.plusMinutes(1));

        assertThat(project.getRepositoryVersion()).isEqualTo("v9");
    }

    @Test
    void mergedPullRequestSynchronizesMergeCommit() {
        Project project = project();
        when(projectRepository.findAllBySourceRepository("octo/repo")).thenReturn(List.of(project));

        handler.handle("pull_request", bytes("""
                {
                  "action": "closed",
                  "repository": {
                    "full_name": "octo/repo",
                    "default_branch": "main"
                  },
                  "pull_request": {
                    "merged": true,
                    "merge_commit_sha": "merge-sha",
                    "title": "Release checkout",
                    "merged_at": "2026-06-14T19:58:00+09:00",
                    "merged_by": {"login": "reviewer"},
                    "user": {"login": "author"},
                    "base": {"ref": "main"}
                  }
                }
                """), receivedAt);

        assertThat(project.getRepositoryHeadSha()).isEqualTo("merge-sha");
        assertThat(project.getRepositoryHeadMessage()).isEqualTo("Release checkout");
        assertThat(project.getRepositoryHeadAuthor()).isEqualTo("reviewer");
        verify(projectRepository).save(project);
    }

    @Test
    void installationDeletedDisconnectsUserAndMarksProjectsAccessDenied() {
        User user = user();
        Project project = project();
        when(userRepository.findByGithubInstallationId(77L)).thenReturn(Optional.of(user));
        when(projectRepository.findAllByOwnerUserIdAndDeletedFalseOrderByUpdatedAtDesc(1L))
                .thenReturn(List.of(project));

        handler.handle("installation", installationPayload("deleted"), receivedAt);

        assertThat(user.getGithubInstallationId()).isNull();
        assertThat(user.getGithubUserAccessToken()).isNull();
        assertThat(project.getRepositoryHealthStatus()).isEqualTo(RepositoryHealthStatus.ACCESS_DENIED);
        verify(userRepository).save(user);
        verify(projectRepository).save(project);
    }

    @Test
    void installationPermissionUpdateRestoresConnectionHealth() {
        User user = user();
        Project project = project();
        project.updateRepositoryHealth(RepositoryHealthStatus.ACCESS_DENIED);
        when(userRepository.findByGithubInstallationId(77L)).thenReturn(Optional.of(user));
        when(projectRepository.findAllByOwnerUserIdAndDeletedFalseOrderByUpdatedAtDesc(1L))
                .thenReturn(List.of(project));

        handler.handle("installation", installationPayload("new_permissions_accepted"), receivedAt);

        assertThat(user.getGithubInstallationId()).isEqualTo(77L);
        assertThat(project.getRepositoryHealthStatus()).isEqualTo(RepositoryHealthStatus.UNKNOWN_ERROR);
        verify(userRepository).save(user);
        verify(projectRepository).save(project);
    }

    private byte[] workflowPayload(String headSha) {
        return bytes("""
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
                """.formatted(headSha));
    }

    private byte[] installationPayload(String action) {
        return bytes("""
                {
                  "action": "%s",
                  "installation": {
                    "id": 77,
                    "account": {"id": 991}
                  }
                }
                """.formatted(action));
    }

    private byte[] versionPayload(String version) {
        return bytes("""
                {
                  "ref": "refs/tags/%s",
                  "after": "tagged-sha",
                  "deleted": false,
                  "repository": {
                    "full_name": "octo/repo",
                    "default_branch": "main"
                  }
                }
                """.formatted(version));
    }

    private byte[] bytes(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8);
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
                RepositoryHealthStatus.UNKNOWN_ERROR,
                false,
                now,
                now
        );
    }

    private User user() {
        return new User(
                1L,
                new GithubId("991"),
                "octo",
                "https://avatars.example/octo",
                77L,
                "access-token",
                "refresh-token",
                LocalDateTime.now().plusHours(1)
        );
    }
}
