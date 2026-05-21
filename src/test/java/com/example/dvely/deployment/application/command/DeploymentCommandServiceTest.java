package com.example.dvely.deployment.application.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.deployment.application.command.dto.DeployCommand;
import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.port.out.GithubPagesPort;
import com.example.dvely.deployment.application.port.out.GithubRepoPort;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.deployment.domain.value.PackageManager;
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
class DeploymentCommandServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthCommandService authCommandService;

    @Mock
    private GithubPagesPort githubPagesPort;

    @Mock
    private GithubActionsPort githubActionsPort;

    @Mock
    private GithubRepoPort githubRepoPort;

    @Mock
    private DeploymentHistoryRepository deploymentHistoryRepository;

    private DeploymentCommandService deploymentCommandService;

    @BeforeEach
    void setUp() {
        deploymentCommandService = new DeploymentCommandService(
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
    void deploy_preservesCustomDomainWhenChangingPagesSource() {
        Project project = boundProject();
        User user = new User(
                1L,
                new GithubId("123"),
                "octo",
                null,
                100L,
                "user-token",
                "refresh-token",
                LocalDateTime.now().plusHours(1)
        );

        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(githubPagesPort.createBranchFromTag(
                "user-token",
                "octo/repo",
                "v1.0.0",
                "release/v1.0.0"
        )).thenReturn("release/v1.0.0");
        when(githubPagesPort.getPages("user-token", "octo/repo"))
                .thenReturn(new GithubPagesPort.PagesInfo(
                        true,
                        "https://octo.github.io/repo/",
                        "gh-pages",
                        "site.example.com"
                ));
        when(githubPagesPort.updatePagesSource(
                "user-token",
                "octo/repo",
                "release/v1.0.0",
                "site.example.com"
        )).thenReturn("https://octo.github.io/repo/");
        when(githubRepoPort.detectPackageManager("user-token", "octo/repo")).thenReturn(PackageManager.NPM);
        when(githubRepoPort.detectNodeVersion("user-token", "octo/repo")).thenReturn("20");
        when(githubRepoPort.detectFrameworkType("user-token", "octo/repo")).thenReturn("vue");
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        deploymentCommandService.deploy(
                1L,
                11L,
                new DeployCommand(DeployTargetType.VERSION, "v1.0.0")
        );

        verify(githubPagesPort).updatePagesSource(
                "user-token",
                "octo/repo",
                "release/v1.0.0",
                "site.example.com"
        );
        verify(githubActionsPort).createOrUpdateWorkflow(
                eq("user-token"),
                eq("octo/repo"),
                eq(DeployWorkflowTemplate.fileName()),
                any(String.class)
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
                "v0.9.0",
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
