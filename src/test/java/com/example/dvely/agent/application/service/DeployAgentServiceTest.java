package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.deployment.application.command.dto.DeployCommand;
import com.example.dvely.deployment.application.facade.DeploymentFacade;
import com.example.dvely.deployment.application.result.DeployResult;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class DeployAgentServiceTest {

    @Test
    void deploysApprovedRequestAfterPushingRequestCommitToPreview() {
        DockerContainerService dockerService = mock(DockerContainerService.class);
        PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
        GithubRepositoryPort githubRepositoryPort = mock(GithubRepositoryPort.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuthCommandService authCommandService = mock(AuthCommandService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        DeploymentFacade deploymentFacade = mock(DeploymentFacade.class);
        DeployAgentService service = new DeployAgentService(
                dockerService,
                previewSessionService,
                githubRepositoryPort,
                userRepository,
                authCommandService,
                projectRepository,
                deploymentFacade,
                mock(InputWaitStore.class),
                new PreviewBranchPushService(dockerService)
        );
        Project project = boundProject();
        when(previewSessionService.findByTaskId("task123"))
                .thenReturn(Optional.of(new PreviewSessionInfo(
                        "session-1",
                        1L,
                        11L,
                        21L,
                        "task123",
                        "container-1",
                        3000,
                        "https://preview.qeploy.com/session-1/",
                        LocalDateTime.now().plusMinutes(30)
                )));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
        when(dockerService.exec("container-1", "[ -d /workspace/app/.git ] && echo yes || echo no"))
                .thenReturn("yes");
        when(deploymentFacade.deploy(
                eq(1L),
                eq(11L),
                eq(new DeployCommand(DeployTargetType.LATEST, null, "task123"))
        )).thenReturn(new DeployResult(
                51L,
                11L,
                "LATEST",
                null,
                "PENDING",
                null,
                LocalDateTime.now()
        ));

        CodeAgentService.CodeResult result = service.execute(
                new AgentStep(AgentType.DEPLOY, Map.of()),
                1L,
                "task123",
                11L
        );

        assertThat(result.summary()).contains("승인된 변경 사항의 배포 요청", "배포 ID: 51");
        verify(dockerService).exec("container-1", "cd /workspace/app && git checkout -B preview");
        verify(dockerService).exec(
                "container-1",
                "cd /workspace/app && git diff --cached --quiet || "
                        + "git commit -m 'feat: apply Qeploy Agent task task123'"
        );
        verify(dockerService).exec("container-1", "cd /workspace/app && git push -u origin preview");
        verify(dockerService, never()).exec(anyString(), contains("--force"));
        verify(dockerService, never()).exec(anyString(), contains("origin main"));
        verify(deploymentFacade).deploy(
                1L,
                11L,
                new DeployCommand(DeployTargetType.LATEST, null, "task123")
        );
    }

    // ── I45 (#45): autoBindRepository's single inline retry on ObjectOptimisticLockingFailureException ──

    @Test
    void autoBindRepositoryRetriesOnceAfterOptimisticLockingFailureAndSucceeds() {
        DockerContainerService dockerService = mock(DockerContainerService.class);
        PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
        GithubRepositoryPort githubRepositoryPort = mock(GithubRepositoryPort.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuthCommandService authCommandService = mock(AuthCommandService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        DeploymentFacade deploymentFacade = mock(DeploymentFacade.class);
        DeployAgentService service = new DeployAgentService(
                dockerService, previewSessionService, githubRepositoryPort, userRepository,
                authCommandService, projectRepository, deploymentFacade, mock(InputWaitStore.class),
                new PreviewBranchPushService(dockerService)
        );
        Project notBound = notBoundProject();
        Project reloaded = notBoundProject();
        when(previewSessionService.findByTaskId("task123")).thenReturn(Optional.empty());
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(notBound), Optional.of(reloaded));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(true);
        when(githubRepositoryPort.getRepository(1L, "octo/my-project")).thenReturn(Optional.of(
                new GithubRepositoryPort.GithubRepository(
                        "octo/my-project", "my-project", "octo", null, false, "main", null)
        ));
        // First save attempt loses the race; reload still shows NOT_BOUND, so the retry reapplies
        // the binding and saves again.
        when(projectRepository.save(notBound)).thenThrow(
                new ObjectOptimisticLockingFailureException(Project.class, 11L));
        when(projectRepository.save(reloaded)).thenAnswer(invocation -> invocation.getArgument(0));
        when(deploymentFacade.deploy(eq(1L), eq(11L), any(DeployCommand.class))).thenReturn(
                new DeployResult(51L, 11L, "LATEST", null, "PENDING", null, LocalDateTime.now())
        );

        service.execute(new AgentStep(AgentType.DEPLOY, Map.of()), 1L, "task123", 11L);

        verify(projectRepository, times(1)).save(notBound);
        verify(projectRepository, times(1)).save(reloaded);
        assertThat(reloaded.getRepositoryBindingStatus()).isEqualTo(RepositoryBindingStatus.BOUND);
    }

    @Test
    void autoBindRepositoryReturnsReloadedProjectWithoutResavingWhenAlreadyBoundAfterRace() {
        DockerContainerService dockerService = mock(DockerContainerService.class);
        PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
        GithubRepositoryPort githubRepositoryPort = mock(GithubRepositoryPort.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuthCommandService authCommandService = mock(AuthCommandService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        DeploymentFacade deploymentFacade = mock(DeploymentFacade.class);
        DeployAgentService service = new DeployAgentService(
                dockerService, previewSessionService, githubRepositoryPort, userRepository,
                authCommandService, projectRepository, deploymentFacade, mock(InputWaitStore.class),
                new PreviewBranchPushService(dockerService)
        );
        Project notBound = notBoundProject();
        when(previewSessionService.findByTaskId("task123")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(true);
        when(githubRepositoryPort.getRepository(1L, "octo/my-project")).thenReturn(Optional.of(
                new GithubRepositoryPort.GithubRepository(
                        "octo/my-project", "my-project", "octo", null, false, "main", null)
        ));
        when(projectRepository.save(notBound)).thenThrow(
                new ObjectOptimisticLockingFailureException(Project.class, 11L));
        // Someone else already bound a repository (any repository) to this project while we
        // were retrying — the precondition autoBindRepository exists to satisfy is already met.
        Project alreadyBound = boundProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(notBound), Optional.of(alreadyBound));
        when(deploymentFacade.deploy(eq(1L), eq(11L), any(DeployCommand.class))).thenReturn(
                new DeployResult(51L, 11L, "LATEST", null, "PENDING", null, LocalDateTime.now())
        );

        service.execute(new AgentStep(AgentType.DEPLOY, Map.of()), 1L, "task123", 11L);

        verify(projectRepository, times(1)).save(notBound);
        // No second save — the reloaded project was already BOUND, so re-applying the binding
        // would be pointless (and could itself race again for no reason).
        verify(projectRepository, never()).save(alreadyBound);
    }

    @Test
    void autoBindRepositoryPropagatesWhenTheRetryAlsoHitsAnOptimisticLockingFailure() {
        DockerContainerService dockerService = mock(DockerContainerService.class);
        PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
        GithubRepositoryPort githubRepositoryPort = mock(GithubRepositoryPort.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuthCommandService authCommandService = mock(AuthCommandService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        DeploymentFacade deploymentFacade = mock(DeploymentFacade.class);
        DeployAgentService service = new DeployAgentService(
                dockerService, previewSessionService, githubRepositoryPort, userRepository,
                authCommandService, projectRepository, deploymentFacade, mock(InputWaitStore.class),
                new PreviewBranchPushService(dockerService)
        );
        Project notBound = notBoundProject();
        Project reloaded = notBoundProject();
        when(previewSessionService.findByTaskId("task123")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(true);
        when(githubRepositoryPort.getRepository(1L, "octo/my-project")).thenReturn(Optional.of(
                new GithubRepositoryPort.GithubRepository(
                        "octo/my-project", "my-project", "octo", null, false, "main", null)
        ));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(notBound), Optional.of(reloaded));
        when(projectRepository.save(notBound)).thenThrow(
                new ObjectOptimisticLockingFailureException(Project.class, 11L));
        when(projectRepository.save(reloaded)).thenThrow(
                new ObjectOptimisticLockingFailureException(Project.class, 11L));

        assertThatThrownBy(() -> service.execute(new AgentStep(AgentType.DEPLOY, Map.of()), 1L, "task123", 11L))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(projectRepository, times(1)).save(notBound);
        verify(projectRepository, times(1)).save(reloaded);
        verify(deploymentFacade, never()).deploy(any(), any(), any());
    }

    @Test
    void autoBindRepositoryFailsFastWhenReloadAfterTheRaceFindsTheProjectGone() {
        // I45 (#45) review follow-up F7: the reload branch inside bindAndSaveWithSingleRetry has
        // its own failure mode distinct from "still NOT_BOUND" (F1 test) and "already BOUND"
        // (F2 test above) — the project was deleted entirely between the failed first save and
        // the retry's reload. That must fail fast with a clear message, not NPE or loop.
        DockerContainerService dockerService = mock(DockerContainerService.class);
        PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
        GithubRepositoryPort githubRepositoryPort = mock(GithubRepositoryPort.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuthCommandService authCommandService = mock(AuthCommandService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        DeploymentFacade deploymentFacade = mock(DeploymentFacade.class);
        DeployAgentService service = new DeployAgentService(
                dockerService, previewSessionService, githubRepositoryPort, userRepository,
                authCommandService, projectRepository, deploymentFacade, mock(InputWaitStore.class),
                new PreviewBranchPushService(dockerService)
        );
        Project notBound = notBoundProject();
        when(previewSessionService.findByTaskId("task123")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(true);
        when(githubRepositoryPort.getRepository(1L, "octo/my-project")).thenReturn(Optional.of(
                new GithubRepositoryPort.GithubRepository(
                        "octo/my-project", "my-project", "octo", null, false, "main", null)
        ));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(notBound), Optional.empty());
        when(projectRepository.save(notBound)).thenThrow(
                new ObjectOptimisticLockingFailureException(Project.class, 11L));

        assertThatThrownBy(() -> service.execute(new AgentStep(AgentType.DEPLOY, Map.of()), 1L, "task123", 11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재조회");

        verify(projectRepository, times(1)).save(notBound);
        verify(deploymentFacade, never()).deploy(any(), any(), any());
    }

    // ── I45 (#45): deployWithSingleRetry's single inline retry on ObjectOptimisticLockingFailureException ──

    @Test
    void deployRetriesOnceAfterOptimisticLockingFailureAndSucceeds() {
        DockerContainerService dockerService = mock(DockerContainerService.class);
        PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
        GithubRepositoryPort githubRepositoryPort = mock(GithubRepositoryPort.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuthCommandService authCommandService = mock(AuthCommandService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        DeploymentFacade deploymentFacade = mock(DeploymentFacade.class);
        DeployAgentService service = new DeployAgentService(
                dockerService, previewSessionService, githubRepositoryPort, userRepository,
                authCommandService, projectRepository, deploymentFacade, mock(InputWaitStore.class),
                new PreviewBranchPushService(dockerService)
        );
        Project project = boundProject();
        when(previewSessionService.findByTaskId("task123")).thenReturn(Optional.empty());
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
        DeployCommand command = new DeployCommand(DeployTargetType.LATEST, null, "task123");
        DeployResult success = new DeployResult(51L, 11L, "LATEST", null, "PENDING", null, LocalDateTime.now());
        when(deploymentFacade.deploy(1L, 11L, command))
                .thenThrow(new ObjectOptimisticLockingFailureException(Project.class, 11L))
                .thenReturn(success);

        CodeAgentService.CodeResult result = service.execute(
                new AgentStep(AgentType.DEPLOY, Map.of()), 1L, "task123", 11L);

        assertThat(result.summary()).contains("배포 ID: 51");
        verify(deploymentFacade, times(2)).deploy(1L, 11L, command);
    }

    @Test
    void deployPropagatesWhenTheRetryAlsoHitsAnOptimisticLockingFailure() {
        DockerContainerService dockerService = mock(DockerContainerService.class);
        PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
        GithubRepositoryPort githubRepositoryPort = mock(GithubRepositoryPort.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuthCommandService authCommandService = mock(AuthCommandService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        DeploymentFacade deploymentFacade = mock(DeploymentFacade.class);
        DeployAgentService service = new DeployAgentService(
                dockerService, previewSessionService, githubRepositoryPort, userRepository,
                authCommandService, projectRepository, deploymentFacade, mock(InputWaitStore.class),
                new PreviewBranchPushService(dockerService)
        );
        Project project = boundProject();
        when(previewSessionService.findByTaskId("task123")).thenReturn(Optional.empty());
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
        DeployCommand command = new DeployCommand(DeployTargetType.LATEST, null, "task123");
        when(deploymentFacade.deploy(1L, 11L, command))
                .thenThrow(new ObjectOptimisticLockingFailureException(Project.class, 11L));

        assertThatThrownBy(() -> service.execute(new AgentStep(AgentType.DEPLOY, Map.of()), 1L, "task123", 11L))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(deploymentFacade, times(2)).deploy(1L, 11L, command);
    }

    private Project notBoundProject() {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L,
                1L,
                "my-project",
                ProjectStatus.ACTIVE,
                "vue",
                null,
                "fast",
                DeployStatus.DRAFT,
                null,
                null,
                null,
                null,
                RepositoryVisibility.PRIVATE,
                RepositoryBindingStatus.NOT_BOUND,
                RepositoryHealthStatus.UNKNOWN_ERROR,
                false,
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
                DeployStatus.PREVIEW_READY,
                null,
                null,
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
