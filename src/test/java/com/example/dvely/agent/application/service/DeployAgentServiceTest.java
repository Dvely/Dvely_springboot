package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                mock(InputWaitStore.class)
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
                eq(new DeployCommand(DeployTargetType.LATEST, null))
        )).thenReturn(new DeployResult(
                51L,
                11L,
                "LATEST",
                "v1",
                "IN_PROGRESS",
                "https://octo.github.io/repo/",
                LocalDateTime.now()
        ));

        CodeAgentService.CodeResult result = service.execute(
                new AgentStep(AgentType.DEPLOY, Map.of()),
                1L,
                "task123",
                11L
        );

        assertThat(result.summary()).contains("승인된 변경 사항", "https://octo.github.io/repo/");
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
                new DeployCommand(DeployTargetType.LATEST, null)
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
