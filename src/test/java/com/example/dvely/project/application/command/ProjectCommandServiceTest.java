package com.example.dvely.project.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.chat.application.command.ChatCommandService;
import com.example.dvely.project.application.command.dto.ConnectProjectRepositoryCommand;
import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.port.out.UserProfilePort;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectRepositoryResult;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.service.ProjectDomainService;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectCommandServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private GithubRepositoryPort githubRepositoryPort;

    @Mock
    private UserProfilePort userProfilePort;

    @Mock
    private ChatCommandService chatCommandService;

    private ProjectCommandService projectCommandService;

    @BeforeEach
    void setUp() {
        projectCommandService = new ProjectCommandService(
                projectRepository,
                new ProjectDomainService(),
                githubRepositoryPort,
                userProfilePort,
                chatCommandService
        );
    }

    @Test
    void createProject_savesEmptyProjectWithoutGithubRepositoryWork() {
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectDetailResult result = projectCommandService.createProject(
                1L,
                new CreateProjectCommand("my-project", "blank", null, "fast")
        );

        assertThat(result.name()).isEqualTo("my-project");
        assertThat(result.startMode()).isEqualTo("blank");
        assertThat(result.draftMode()).isEqualTo("fast");

        verify(projectRepository).save(any(Project.class));
        verifyNoInteractions(githubRepositoryPort, userProfilePort);
    }

    @Test
    void createProject_normalizesTemplateAndDefaultsDraftMode() {
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectDetailResult result = projectCommandService.createProject(
                1L,
                new CreateProjectCommand("store", " TEMPLATE ", "E Commerce", null)
        );

        assertThat(result.startMode()).isEqualTo("template");
        assertThat(result.templateType()).isEqualTo("e-commerce");
        assertThat(result.draftMode()).isEqualTo("fast");
    }

    @Test
    void createProject_rejectsTemplateWithoutTemplateType() {
        assertThatThrownBy(() -> projectCommandService.createProject(
                1L,
                new CreateProjectCommand("store", "template", null, "quality")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("templateType");
    }

    @Test
    void createProject_rejectsUnknownDraftMode() {
        assertThatThrownBy(() -> projectCommandService.createProject(
                1L,
                new CreateProjectCommand("store", "blank", null, "balanced")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draftMode");
    }

    @Test
    void connectRepository_bindsExistingRepositoryToEmptyProject() {
        Project project = emptyProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(githubRepositoryPort.getRepository(1L, "octo/repo"))
                .thenReturn(Optional.of(new GithubRepositoryPort.GithubRepository(
                        "octo/repo",
                        "repo",
                        "octo",
                        "test repo",
                        false,
                        "main",
                        OffsetDateTime.now()
                )));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectRepositoryResult result = projectCommandService.connectRepository(
                1L,
                11L,
                new ConnectProjectRepositoryCommand("existing", null, "octo/repo", null)
        );

        assertThat(result.projectId()).isEqualTo(11L);
        assertThat(result.repositoryFullName()).isEqualTo("octo/repo");
        assertThat(result.repositoryVisibility()).isEqualTo("PUBLIC");
        assertThat(result.bindingStatus()).isEqualTo("BOUND");
        assertThat(result.repositoryHealth()).isEqualTo("HEALTHY");

        verify(githubRepositoryPort).preparePreviewBranch(1L, "octo/repo");
        verify(githubRepositoryPort, never()).createRepository(eq(1L), any(), any());
    }

    @Test
    void connectRepository_createsRepositoryAndBindsItToEmptyProject() {
        Project project = emptyProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(userProfilePort.getGithubLogin(1L)).thenReturn("octo");
        when(githubRepositoryPort.repositoryExists(1L, "octo/new-repo")).thenReturn(false);
        when(githubRepositoryPort.createRepository(1L, "new-repo", RepositoryVisibility.PRIVATE))
                .thenReturn("octo/new-repo");
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectRepositoryResult result = projectCommandService.connectRepository(
                1L,
                11L,
                new ConnectProjectRepositoryCommand("create", "new-repo", null, "PRIVATE")
        );

        assertThat(result.repositoryFullName()).isEqualTo("octo/new-repo");
        assertThat(result.repositoryVisibility()).isEqualTo("PRIVATE");
        assertThat(result.bindingStatus()).isEqualTo("BOUND");

        verify(githubRepositoryPort).preparePreviewBranch(1L, "octo/new-repo");
    }

    @Test
    void connectRepository_rejectsAlreadyBoundProject() {
        Project project = boundProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> projectCommandService.connectRepository(
                1L,
                11L,
                new ConnectProjectRepositoryCommand("existing", null, "octo/other", null)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 GitHub 저장소가 연결된 프로젝트입니다");

        verifyNoInteractions(githubRepositoryPort, userProfilePort);
    }

    private Project emptyProject() {
        return project(null, null, RepositoryVisibility.PRIVATE, RepositoryBindingStatus.NOT_BOUND);
    }

    private Project boundProject() {
        return project("octo/repo", "octo/repo", RepositoryVisibility.PUBLIC, RepositoryBindingStatus.BOUND);
    }

    private Project project(String sourceRepository,
                            String deploymentRepository,
                            RepositoryVisibility visibility,
                            RepositoryBindingStatus bindingStatus) {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L,
                1L,
                "my-project",
                ProjectStatus.DRAFT,
                "blank",
                null,
                "fast",
                DeployStatus.DRAFT,
                null,
                null,
                sourceRepository,
                deploymentRepository,
                visibility,
                bindingStatus,
                RepositoryHealthStatus.UNKNOWN_ERROR,
                false,
                now,
                now
        );
    }
}
