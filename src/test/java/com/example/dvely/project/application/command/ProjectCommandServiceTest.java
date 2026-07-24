package com.example.dvely.project.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.chat.application.command.ChatCommandService;
import com.example.dvely.project.application.command.dto.ConnectProjectRepositoryCommand;
import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.port.out.UserProfilePort;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectRepositoryResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
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
import org.mockito.ArgumentCaptor;
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

    @Mock
    private AuditRecorder auditRecorder;

    private ProjectCommandService projectCommandService;

    @BeforeEach
    void setUp() {
        projectCommandService = new ProjectCommandService(
                projectRepository,
                new ProjectDomainService(),
                githubRepositoryPort,
                userProfilePort,
                chatCommandService,
                auditRecorder
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
        // ProjectRepositoryResult itself has no connectedAt field, so the V24/D4 stamp is
        // verified on the saved domain object — this is what the repository settings screen
        // (GET .../settings/repository) reads back later.
        assertThat(project.getRepositoryConnectedAt()).isNotNull();

        verify(githubRepositoryPort).preparePreviewBranch(1L, "octo/repo");
        verify(githubRepositoryPort, never()).createRepository(eq(1L), any(), any());
        // H1 (design §4): "existing" mode connects an already-existing repository —
        // REPOSITORY_CONNECTED, not REPOSITORY_CREATED.
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.REPOSITORY_CONNECTED);
        assertThat(auditCaptor.getValue().resourceId()).isEqualTo("octo/repo");
        assertThat(auditCaptor.getValue().projectId()).isEqualTo(11L);
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
        assertThat(project.getRepositoryConnectedAt()).isNotNull();

        verify(githubRepositoryPort).preparePreviewBranch(1L, "octo/new-repo");
        // H1: "create" mode actually created a new repository — REPOSITORY_CREATED.
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.REPOSITORY_CREATED);
        assertThat(auditCaptor.getValue().resourceId()).isEqualTo("octo/new-repo");
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

        verifyNoInteractions(githubRepositoryPort, userProfilePort, auditRecorder);
    }

    @Test
    void disconnectRepository_clearsRepositoryFieldsButKeepsDeploymentArtifacts() {
        LocalDateTime now = LocalDateTime.now();
        Project project = emptyProject();
        project.bindRepository("octo/repo", RepositoryVisibility.PUBLIC);
        project.synchronizeRepositoryHead("abc123", "feat: init", "octo", now, now);
        project.synchronizeRepositoryVersion("v3", now);
        // Simulates a project that has already deployed once before the repository is
        // disconnected — this is the scenario D7 exists for: the GitHub Pages site keeps
        // serving the old build, so the deployment snapshot must survive unbindRepository().
        project.updateDeployment(DeployStatus.LIVE, "https://octo.github.io/repo/", "v3");
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        projectCommandService.disconnectRepository(1L, 11L);

        ArgumentCaptor<Project> savedCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(savedCaptor.capture());
        Project saved = savedCaptor.getValue();

        assertThat(saved.hasSourceRepository()).isFalse();
        assertThat(saved.getSourceRepository()).isNull();
        assertThat(saved.getDeploymentRepository()).isNull();
        assertThat(saved.getRepositoryVisibility()).isEqualTo(RepositoryVisibility.PRIVATE);
        assertThat(saved.getRepositoryBindingStatus()).isEqualTo(RepositoryBindingStatus.NOT_BOUND);
        assertThat(saved.getRepositoryHealthStatus()).isEqualTo(RepositoryHealthStatus.UNKNOWN_ERROR);
        assertThat(saved.getRepositoryHeadSha()).isNull();
        assertThat(saved.getRepositoryHeadMessage()).isNull();
        assertThat(saved.getRepositoryHeadAuthor()).isNull();
        assertThat(saved.getRepositoryHeadCommittedAt()).isNull();
        assertThat(saved.getRepositoryHeadSyncedAt()).isNull();
        assertThat(saved.getRepositoryVersion()).isNull();
        assertThat(saved.getRepositoryVersionSyncedAt()).isNull();
        assertThat(saved.getRepositoryConnectedAt()).isNull();

        assertThat(saved.getDeployStatus()).isEqualTo(DeployStatus.LIVE);
        assertThat(saved.getCurrentUrl()).isEqualTo("https://octo.github.io/repo/");
        assertThat(saved.getCurrentVersion()).isEqualTo("v3");

        verifyNoInteractions(githubRepositoryPort);
        // H2 (design §4): resource_id must be the repository that *was* connected — read before
        // unbindRepository() clears it, not the null it becomes afterward.
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.REPOSITORY_DISCONNECTED);
        assertThat(auditCaptor.getValue().resourceId()).isEqualTo("octo/repo");
    }

    @Test
    void disconnectRepository_rejectsProjectWithoutRepository() {
        Project project = emptyProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> projectCommandService.disconnectRepository(1L, 11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("프로젝트에 연결된 저장소가 없습니다");

        verify(projectRepository, never()).save(any());
        verifyNoInteractions(githubRepositoryPort, auditRecorder);
    }

    @Test
    void disconnectRepository_rejectsOtherUsersProject() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectCommandService.disconnectRepository(1L, 11L))
                .isInstanceOf(ProjectNotFoundException.class);

        verify(projectRepository, never()).save(any());
        verifyNoInteractions(githubRepositoryPort, auditRecorder);
    }

    @Test
    void disconnectRepository_thenConnectRepository_allowsReconnectingSameProject() {
        Project project = boundProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        projectCommandService.disconnectRepository(1L, 11L);

        when(githubRepositoryPort.getRepository(1L, "octo/new-repo"))
                .thenReturn(Optional.of(new GithubRepositoryPort.GithubRepository(
                        "octo/new-repo",
                        "new-repo",
                        "octo",
                        "test repo",
                        false,
                        "main",
                        OffsetDateTime.now()
                )));

        // The disconnect above must have driven the project back to hasSourceRepository()==false
        // so that connectRepository's "already connected" 409 guard does not block reconnection.
        ProjectRepositoryResult result = projectCommandService.connectRepository(
                1L,
                11L,
                new ConnectProjectRepositoryCommand("existing", null, "octo/new-repo", null)
        );

        assertThat(result.repositoryFullName()).isEqualTo("octo/new-repo");
        assertThat(result.bindingStatus()).isEqualTo("BOUND");
        verify(githubRepositoryPort).preparePreviewBranch(1L, "octo/new-repo");
    }

    @Test
    void deleteProject_withRepositoryMode_deletesGithubRepositoryAndRecordsAudit() {
        Project project = boundProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        projectCommandService.deleteProject(1L, 11L, com.example.dvely.project.application.command.dto.ProjectDeleteMode.PROJECT_AND_REPOSITORY);

        verify(githubRepositoryPort).deleteRepository(1L, "octo/repo");
        // H3 (design §4): recorded right after the real GitHub deletion succeeds.
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.REPOSITORY_DELETED);
        assertThat(auditCaptor.getValue().resourceId()).isEqualTo("octo/repo");
        assertThat(auditCaptor.getValue().projectId()).isEqualTo(11L);
    }

    @Test
    void deleteProject_recorderThrowing_doesNotPreventGithubRepositoryDeletion() {
        // H3 is one of the hooks the design explicitly calls out (§11) for the "recorder throws
        // does not break the caller's own operation" property — AuditRecorder itself never
        // propagates (see AuditRecorderIntegrationTest#recordNeverThrowsEvenWhenTheUnderlyingWriteFails),
        // but this pins that ProjectCommandService also does not add its own try/catch that could
        // mask a future regression there.
        Project project = boundProject();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        // No projectRepository.save() stub here (unlike the happy-path test above): the audit
        // hook runs before that save (see ProjectCommandService#deleteProjectAndRepository), so
        // once the mocked recorder throws, execution never reaches it — stubbing it anyway would
        // be an unused stub under Mockito's strict-stubbing default.
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(auditRecorder).record(any());

        assertThatThrownBy(() -> projectCommandService.deleteProject(
                1L, 11L, com.example.dvely.project.application.command.dto.ProjectDeleteMode.PROJECT_AND_REPOSITORY))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        // The GitHub deletion itself must already have gone through before the (mocked, in real
        // life never-throwing) recorder call — this assertion documents that ordering rather than
        // asserting AuditRecorder's own contract (that belongs to
        // AuditRecorderIntegrationTest#recordNeverThrowsEvenWhenTheUnderlyingWriteFails).
        verify(githubRepositoryPort).deleteRepository(1L, "octo/repo");
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
