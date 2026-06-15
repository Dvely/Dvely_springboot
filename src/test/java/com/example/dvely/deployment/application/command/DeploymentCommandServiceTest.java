package com.example.dvely.deployment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.deployment.application.command.dto.DeployCommand;
import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.port.out.GithubActionsPort.WorkflowRunMatch;
import com.example.dvely.deployment.application.port.out.GithubPagesPort;
import com.example.dvely.deployment.application.port.out.GithubRepoPort;
import com.example.dvely.deployment.application.port.out.GithubRepoPort.ReleaseMetadata;
import com.example.dvely.deployment.application.result.DeployResult;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.deployment.domain.value.PackageManager;
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
class DeploymentCommandServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthCommandService authCommandService;
    @Mock private GithubPagesPort githubPagesPort;
    @Mock private GithubActionsPort githubActionsPort;
    @Mock private GithubRepoPort githubRepoPort;
    @Mock private DeploymentHistoryRepository deploymentHistoryRepository;

    private DeploymentCommandService service;

    @BeforeEach
    void setUp() {
        service = new DeploymentCommandService(
                projectRepository,
                userRepository,
                authCommandService,
                githubPagesPort,
                githubActionsPort,
                githubRepoPort,
                deploymentHistoryRepository
        );
    }

    @Test
    void deploy_persistsPendingJobWithoutCallingGithub() {
        Project project = boundProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> persisted(invocation.getArgument(0), 51L));
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeployResult result = service.deploy(
                1L,
                11L,
                new DeployCommand(DeployTargetType.LATEST, null)
        );

        assertThat(result.deploymentId()).isEqualTo(51L);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.versionName()).isNull();
        assertThat(result.pagesUrl()).isNull();
        assertThat(project.getDeployStatus()).isEqualTo(DeployStatus.PENDING);
        verifyNoInteractions(userRepository, githubPagesPort, githubActionsPort, githubRepoPort);
    }

    @Test
    void execute_reusesExistingSequentialTagAndStoresReleaseMetadata() {
        Project project = boundProject();
        DeploymentHistory history = claimedHistory();
        ReleaseMetadata metadata = new ReleaseMetadata(
                "abc123",
                "Release title",
                "Release body",
                "octo",
                "https://avatars.example/octo",
                17,
                LocalDateTime.of(2026, 6, 10, 9, 30)
        );
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(history));
        when(deploymentHistoryRepository.findLatestByProjectId(11L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepoPort.detectPackageManager("user-token", "octo/repo"))
                .thenReturn(PackageManager.NPM);
        when(githubRepoPort.detectNodeVersion("user-token", "octo/repo")).thenReturn("20");
        when(githubRepoPort.detectFrameworkType("user-token", "octo/repo")).thenReturn("vue");
        when(githubRepoPort.hasNewCommits("user-token", "octo/repo", "main", "preview"))
                .thenReturn(false);
        when(githubRepoPort.getHeadCommitSha("user-token", "octo/repo", "main"))
                .thenReturn("abc123");
        when(githubRepoPort.findSequentialTagForCommit("user-token", "octo/repo", "abc123"))
                .thenReturn("v7");
        when(githubRepoPort.getReleaseMetadata("user-token", "octo/repo", "abc123", null))
                .thenReturn(metadata);
        when(githubPagesPort.getPages("user-token", "octo/repo"))
                .thenReturn(new GithubPagesPort.PagesInfo(
                        true,
                        "https://octo.github.io/repo/",
                        "gh-pages",
                        "site.example.com"
                ));
        when(githubActionsPort.findWorkflowRun(
                "user-token",
                "octo/repo",
                "qeploy-deploy.yml",
                "correlation-51",
                "abc123",
                history.getTriggeredAt()
        )).thenReturn(new WorkflowRunMatch(901L, "abc123", "queued", null));
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.execute(51L);

        assertThat(history.getVersionLabel()).isEqualTo("v7");
        assertThat(history.getCommitSha()).isEqualTo("abc123");
        assertThat(history.getTitle()).isEqualTo("Release title");
        assertThat(history.getPrNumber()).isEqualTo(17);
        assertThat(history.getWorkflowRunId()).isEqualTo(901L);
        assertThat(history.getStatus()).isEqualTo(DeployStatus.IN_PROGRESS);
        verify(githubRepoPort, never())
                .createNextSequentialTag("user-token", "octo/repo", "abc123");
        verify(githubActionsPort, never()).triggerWorkflow(
                "user-token",
                "octo/repo",
                "qeploy-deploy.yml",
                "main",
                "main",
                "correlation-51"
        );
    }

    private DeploymentHistory persisted(DeploymentHistory source, Long id) {
        return new DeploymentHistory(
                id,
                source.getOwnerUserId(),
                source.getProjectId(),
                source.getDeployTargetType(),
                source.getVersionLabel(),
                source.getDeployedUrl(),
                source.getStatus(),
                source.getWorkflowRunId(),
                source.getCorrelationId(),
                source.getCommitSha(),
                source.getWorkflowHeadSha(),
                source.getTitle(),
                source.getDescription(),
                source.getMergedBy(),
                source.getMergedByAvatarUrl(),
                source.getPrNumber(),
                source.getMergedAt(),
                source.getTaskId(),
                source.getErrorMessage(),
                source.getAttempt(),
                source.getMaxAttempts(),
                source.getNextRunAt(),
                source.getLeaseOwner(),
                source.getLeaseUntil(),
                source.getTriggeredAt(),
                source.getUpdatedAt()
        );
    }

    private DeploymentHistory claimedHistory() {
        LocalDateTime now = LocalDateTime.now();
        return new DeploymentHistory(
                51L,
                1L,
                11L,
                DeployTargetType.LATEST,
                null,
                null,
                DeployStatus.IN_PROGRESS,
                null,
                "correlation-51",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "task-51",
                null,
                1,
                3,
                null,
                "worker-1",
                now.plusMinutes(2),
                now,
                now
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
                DeployStatus.LIVE,
                "https://octo.github.io/repo/",
                "v6",
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
