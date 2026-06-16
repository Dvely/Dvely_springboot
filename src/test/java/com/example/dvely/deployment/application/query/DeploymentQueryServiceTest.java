package com.example.dvely.deployment.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.port.out.GithubActionsPort.WorkflowRunMatch;
import com.example.dvely.deployment.application.port.out.GithubActionsPort.WorkflowRunStatus;
import com.example.dvely.deployment.application.result.DeploymentStatusResult;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeploymentQueryServiceTest {

    @Mock
    private DeploymentHistoryRepository deploymentHistoryRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GithubActionsPort githubActionsPort;

    private DeploymentQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new DeploymentQueryService(
                deploymentHistoryRepository,
                projectRepository,
                userRepository,
                githubActionsPort
        );
    }

    @Test
    void getDeploymentStatus_fallsBackToLegacyWorkflowForExistingRuns() {
        LocalDateTime triggeredAt = LocalDateTime.now().minusMinutes(1);
        DeploymentHistory history = new DeploymentHistory(
                101L,
                11L,
                DeployTargetType.LATEST,
                "v1.0.0",
                "https://octo.github.io/repo/",
                DeployStatus.IN_PROGRESS,
                null,
                triggeredAt,
                triggeredAt
        );
        Project project = boundProject();
        User user = activeUser();

        when(deploymentHistoryRepository.findById(101L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(githubActionsPort.getLatestRunStatus(
                "user-token",
                "octo/repo",
                DeployWorkflowTemplate.fileName(),
                triggeredAt
        )).thenReturn(new WorkflowRunStatus(null, "queued", null));
        when(githubActionsPort.getLatestRunStatus(
                "user-token",
                "octo/repo",
                DeployWorkflowTemplate.legacyFileName(),
                triggeredAt
        )).thenReturn(new WorkflowRunStatus(501L, "in_progress", null));

        DeploymentStatusResult result = queryService.getDeploymentStatus(1L, 101L);

        assertThat(result.buildStatus()).isEqualTo("in_progress");
        verify(githubActionsPort).getLatestRunStatus(
                "user-token",
                "octo/repo",
                DeployWorkflowTemplate.legacyFileName(),
                triggeredAt
        );
    }

    @Test
    void getDeploymentStatus_usesCorrelationForNewRunWithoutAssignedRunId() {
        DeploymentHistory history = deploymentHistoryWithMetadata(
                DeployStatus.IN_PROGRESS,
                null,
                "correlation-101",
                "workflow-sha"
        );
        Project project = boundProject();
        User user = activeUser();
        when(deploymentHistoryRepository.findById(101L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(githubActionsPort.findWorkflowRun(
                "user-token",
                "octo/repo",
                DeployWorkflowTemplate.fileName(),
                "correlation-101",
                "workflow-sha",
                history.getTriggeredAt()
        )).thenReturn(new WorkflowRunMatch(501L, "workflow-sha", "in_progress", null));

        DeploymentStatusResult result = queryService.getDeploymentStatus(1L, 101L);

        assertThat(result.buildStatus()).isEqualTo("in_progress");
        verify(githubActionsPort).findWorkflowRun(
                "user-token",
                "octo/repo",
                DeployWorkflowTemplate.fileName(),
                "correlation-101",
                "workflow-sha",
                history.getTriggeredAt()
        );
    }

    @Test
    void getVersions_returnsStoredReleaseMetadata() {
        DeploymentHistory history = deploymentHistoryWithMetadata(
                DeployStatus.LIVE,
                501L,
                "correlation-101",
                "workflow-sha"
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(boundProject()));
        when(deploymentHistoryRepository.findByProjectIdOrderByTriggeredAtDesc(11L))
                .thenReturn(List.of(history));

        var versions = queryService.getVersions(1L, 11L);
        var detail = queryService.getVersionDetail(1L, 101L);

        assertThat(versions).singleElement().satisfies(version -> {
            assertThat(version.commitSha()).isEqualTo("release-sha");
            assertThat(version.title()).isEqualTo("Release title");
        });
        assertThat(detail.mergedBy()).isEqualTo("octo");
        assertThat(detail.prNumber()).isEqualTo(17);
    }

    @Test
    void getDeploymentStatus_rejectsHistoryOwnedByAnotherUser() {
        DeploymentHistory history = deploymentHistory(DeployStatus.IN_PROGRESS, 501L);
        when(deploymentHistoryRepository.findById(101L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getDeploymentStatus(2L, 101L))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(userRepository, githubActionsPort);
    }

    @Test
    void getDeploymentHistories_rejectsProjectOwnedByAnotherUser() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getDeploymentHistories(2L, 11L))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(deploymentHistoryRepository);
    }

    @Test
    void getVersions_rejectsProjectOwnedByAnotherUser() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getVersions(2L, 11L))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(deploymentHistoryRepository);
    }

    @Test
    void getVersionDetail_rejectsHistoryOwnedByAnotherUser() {
        when(deploymentHistoryRepository.findById(101L))
                .thenReturn(Optional.of(deploymentHistory(DeployStatus.LIVE, 501L)));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getVersionDetail(2L, 101L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getDeploymentCandidates_rejectsProjectOwnedByAnotherUser() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getDeploymentCandidates(2L, 11L))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(deploymentHistoryRepository);
    }

    @Test
    void getDeploymentLogs_rejectsHistoryOwnedByAnotherUser() {
        when(deploymentHistoryRepository.findById(101L))
                .thenReturn(Optional.of(deploymentHistory(DeployStatus.LIVE, 501L)));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getDeploymentLogs(2L, 101L))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(userRepository, githubActionsPort);
    }

    private DeploymentHistory deploymentHistory(DeployStatus status, Long workflowRunId) {
        LocalDateTime now = LocalDateTime.now();
        return new DeploymentHistory(
                101L,
                11L,
                DeployTargetType.LATEST,
                "v1.0.0",
                "https://octo.github.io/repo/",
                status,
                workflowRunId,
                now,
                now
        );
    }

    private DeploymentHistory deploymentHistoryWithMetadata(DeployStatus status,
                                                              Long workflowRunId,
                                                              String correlationId,
                                                              String workflowHeadSha) {
        LocalDateTime now = LocalDateTime.now();
        DeploymentHistory history = new DeploymentHistory(
                101L,
                1L,
                11L,
                DeployTargetType.LATEST,
                "v7",
                "https://octo.github.io/repo/",
                status,
                workflowRunId,
                correlationId,
                "release-sha",
                workflowHeadSha,
                "Release title",
                "Release description",
                "octo",
                "https://avatars.example/octo",
                17,
                now.minusMinutes(5),
                "task-101",
                null,
                1,
                3,
                null,
                null,
                null,
                now,
                now
        );
        when(deploymentHistoryRepository.findById(101L)).thenReturn(Optional.of(history));
        return history;
    }

    private Project boundProject() {
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
                "v1.0.0",
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

    private User activeUser() {
        return new User(
                1L,
                new GithubId("123"),
                "octo",
                null,
                100L,
                "user-token",
                "refresh-token",
                LocalDateTime.now().plusHours(1)
        );
    }
}
