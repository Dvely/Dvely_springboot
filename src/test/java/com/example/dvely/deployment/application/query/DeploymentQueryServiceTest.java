package com.example.dvely.deployment.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.deployment.application.port.out.GithubActionsPort;
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
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
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

        DeploymentStatusResult result = queryService.getDeploymentStatus(101L);

        assertThat(result.buildStatus()).isEqualTo("in_progress");
        verify(githubActionsPort).getLatestRunStatus(
                "user-token",
                "octo/repo",
                DeployWorkflowTemplate.legacyFileName(),
                triggeredAt
        );
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
