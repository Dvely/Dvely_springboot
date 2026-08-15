package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.service.RepositoryProvisioningService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

class RepositoryBindingServiceTest {

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
    private final PreviewBranchPushService previewBranchPushService = mock(PreviewBranchPushService.class);
    private final GithubRepositoryPort githubRepositoryPort = mock(GithubRepositoryPort.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthCommandService authCommandService = mock(AuthCommandService.class);
    private final AuditRecorder auditRecorder = mock(AuditRecorder.class);

    // 연결 마지막 순서(preview 브랜치 준비 → 바인딩 → 저장 → 감사)는 설정 화면 경로와 공유하는
    // 실제 구현을 물린다. mock 으로 막으면 preparePreviewBranch 누락 같은 회귀를 못 잡는다.
    private final RepositoryProvisioningService repositoryProvisioningService =
            new RepositoryProvisioningService(githubRepositoryPort, projectRepository, auditRecorder);

    private final RepositoryBindingService service = new RepositoryBindingService(
            projectRepository, previewSessionService, previewBranchPushService, githubRepositoryPort,
            repositoryProvisioningService, userRepository, authCommandService, auditRecorder
    );

    @Test
    void createsTheRepositoryBindsTheProjectThenPushesThePreviewBranch() {
        Approval approval = approval();
        stubNotBoundProject("my-project");
        stubPreviewSessionAndUser();
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(false);
        when(githubRepositoryPort.createRepository(1L, "my-project", RepositoryVisibility.PUBLIC))
                .thenReturn("octo/my-project");

        RepositoryBindingService.BindResult result = service.bind(approval, "my-project");

        assertThat(result).isEqualTo(
                new RepositoryBindingService.BindResult("octo/my-project", true, true));
        verify(previewBranchPushService).push("container-1", "gh-token", "octo", "octo/my-project", true, "task-1");
        // Binding is persisted before the push, so a push failure cannot leave GitHub holding the
        // code while the project rolls back to NOT_BOUND.
        InOrder order = Mockito.inOrder(githubRepositoryPort, projectRepository, previewBranchPushService);
        order.verify(githubRepositoryPort).createRepository(anyLong(), anyString(), any());
        order.verify(projectRepository).save(any(Project.class));
        order.verify(previewBranchPushService).push(anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void bindsTheProjectAsBoundAndPublicWithHealthyStatus() {
        Approval approval = approval();
        stubNotBoundProject("my-project");
        stubPreviewSessionAndUser();
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(false);
        when(githubRepositoryPort.createRepository(1L, "my-project", RepositoryVisibility.PUBLIC))
                .thenReturn("octo/my-project");

        service.bind(approval, "my-project");

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertThat(captor.getValue().getRepositoryBindingStatus()).isEqualTo(RepositoryBindingStatus.BOUND);
        assertThat(captor.getValue().getSourceRepository()).isEqualTo("octo/my-project");
        assertThat(captor.getValue().getRepositoryVisibility()).isEqualTo(RepositoryVisibility.PUBLIC);
        assertThat(captor.getValue().getRepositoryHealthStatus()).isEqualTo(RepositoryHealthStatus.HEALTHY);
    }

    @Test
    void recordsCreatedAndPushedAuditEventsForANewRepository() {
        Approval approval = approval();
        stubNotBoundProject("my-project");
        stubPreviewSessionAndUser();
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(false);
        when(githubRepositoryPort.createRepository(1L, "my-project", RepositoryVisibility.PUBLIC))
                .thenReturn("octo/my-project");

        service.bind(approval, "my-project");

        // 설정 화면 경로와 같은 관례로 연결 자체는 한 건만 남긴다(만들었으면 CREATED, 기존 것을
        // 썼으면 CONNECTED). push 는 별개의 외부 작업이라 따로 기록한다.
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder, Mockito.times(2)).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditEvent::action).containsExactly(
                AuditAction.REPOSITORY_CREATED,
                AuditAction.PREVIEW_BRANCH_PUSHED
        );
        assertThat(captor.getAllValues()).allSatisfy(event -> {
            assertThat(event.resourceId()).isEqualTo("octo/my-project");
            assertThat(event.taskId()).isEqualTo("task-1");
        });
    }

    @Test
    void branchesPreviewOffTheDefaultBranchBeforeBinding() {
        // 이 호출을 빠뜨리면 저장소의 기본 브랜치와 preview 가 공통 조상 없이 갈라진다. 그러면
        // 나중에 preview 를 기본 브랜치로 병합할 때 GitHub 비교 API 가 404 를 주고, 그게
        // "병합할 것 없음"으로 해석되어 반영이 조용히 생략된다.
        Approval approval = approval();
        stubNotBoundProject("my-project");
        stubPreviewSessionAndUser();
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(false);
        when(githubRepositoryPort.createRepository(1L, "my-project", RepositoryVisibility.PUBLIC))
                .thenReturn("octo/my-project");

        service.bind(approval, "my-project");

        verify(githubRepositoryPort).preparePreviewBranch(1L, "octo/my-project");
        // 저장소가 생긴 뒤, 그리고 컨테이너가 push 하기 전이어야 한다. 순서가 뒤집히면 로컬
        // 히스토리가 먼저 올라가 원격 preview 와 갈라진다.
        InOrder order = Mockito.inOrder(githubRepositoryPort, previewBranchPushService);
        order.verify(githubRepositoryPort).createRepository(anyLong(), anyString(), any());
        order.verify(githubRepositoryPort).preparePreviewBranch(anyLong(), anyString());
        order.verify(previewBranchPushService).push(anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void reusesAnExistingRepositoryWithoutCreatingOrAuditingACreation() {
        Approval approval = approval();
        stubNotBoundProject("my-project");
        stubPreviewSessionAndUser();
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(true);

        RepositoryBindingService.BindResult result = service.bind(approval, "my-project");

        assertThat(result.created()).isFalse();
        assertThat(result.pushed()).isTrue();
        verify(githubRepositoryPort, never()).createRepository(anyLong(), anyString(), any());
        // isNew stays true even though the repository already existed on GitHub: it describes the
        // CONTAINER, not the remote. This gate only fires for NOT_BOUND projects, whose containers
        // CodeAgentService never cloned into — so there is no .git to reuse, and passing false
        // here would skip the .gitignore and commit node_modules/dist/.env into a PUBLIC repo.
        verify(previewBranchPushService).push("container-1", "gh-token", "octo", "octo/my-project", true, "task-1");
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder, Mockito.times(2)).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditEvent::action)
                .doesNotContain(AuditAction.REPOSITORY_CREATED);
    }

    @Test
    void acceptsAProjectThatGotBoundThroughAnotherPathWhileTheApprovalWasPending() {
        // The settings screen (POST /projects/{id}/repository) or a DEPLOY step can connect the
        // project between the gate firing and the user deciding. The goal is already met, so this
        // must not create a second repository — and pushed=false tells the caller not to claim it
        // did any GitHub work.
        Approval approval = approval();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project("my-project", RepositoryBindingStatus.BOUND)));

        RepositoryBindingService.BindResult result = service.bind(approval, "some-other-name");

        assertThat(result).isEqualTo(
                new RepositoryBindingService.BindResult("octo/repo", false, false));
        verifyNoInteractions(githubRepositoryPort, previewBranchPushService, auditRecorder);
        verify(projectRepository, never()).save(any(Project.class));
    }

    @Test
    void fallsBackToTheProjectDerivedCandidateWhenNoNameWasRequested() {
        // A body-less approve arrives as null — the repository must end up named exactly what the
        // gate already showed the user in the approval summary.
        Approval approval = approval();
        stubNotBoundProject("My Project");
        stubPreviewSessionAndUser();
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(false);
        when(githubRepositoryPort.createRepository(1L, "my-project", RepositoryVisibility.PUBLIC))
                .thenReturn("octo/my-project");

        service.bind(approval, null);

        verify(githubRepositoryPort).createRepository(1L, "my-project", RepositoryVisibility.PUBLIC);
    }

    @Test
    void fallsBackToTheCandidateWhenTheRequestedNameSanitisesToNothing() {
        Approval approval = approval();
        stubNotBoundProject("My Project");
        stubPreviewSessionAndUser();
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(false);
        when(githubRepositoryPort.createRepository(1L, "my-project", RepositoryVisibility.PUBLIC))
                .thenReturn("octo/my-project");

        service.bind(approval, "   ");

        verify(githubRepositoryPort).createRepository(1L, "my-project", RepositoryVisibility.PUBLIC);
    }

    @Test
    void sanitisesTheRequestedNameBeforeSendingItToGithub() {
        Approval approval = approval();
        stubNotBoundProject("my-project");
        stubPreviewSessionAndUser();
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-shiny-repo")).thenReturn(false);
        when(githubRepositoryPort.createRepository(1L, "my-shiny-repo", RepositoryVisibility.PUBLIC))
                .thenReturn("octo/my-shiny-repo");

        service.bind(approval, "My Shiny Repo!!");

        verify(githubRepositoryPort).createRepository(1L, "my-shiny-repo", RepositoryVisibility.PUBLIC);
    }

    @Test
    void refreshesAnExpiredGithubTokenBeforePushing() {
        Approval approval = approval();
        stubNotBoundProject("my-project");
        stubPreviewSession();
        User expired = new User(1L, new GithubId("123"), "octo", null, 100L, "stale-token",
                "refresh-token", LocalDateTime.now().minusMinutes(1));
        User refreshed = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(expired), Optional.of(refreshed));
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(true);

        service.bind(approval, "my-project");

        verify(authCommandService).refreshGithubUserToken(1L);
        // The push must use the token from the reloaded user, not the stale one it started with.
        verify(previewBranchPushService).push(anyString(), eq("gh-token"), anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void failsBeforeCreatingAnyRepositoryWhenThePreviewSessionHasAlreadyExpired() {
        // The gate only fires with a live session, but the container TTL (30m by default) can
        // expire while the approval waits for a human. That check must happen before the
        // irreversible createRepository call — a rollback only undoes the DB, so the reverse order
        // would leave an orphaned empty repository on GitHub every time this races.
        Approval approval = approval();
        stubNotBoundProject("my-project");
        when(previewSessionService.findByTaskId("task-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bind(approval, "my-project"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task-1");

        verifyNoInteractions(githubRepositoryPort, previewBranchPushService, auditRecorder);
        verify(projectRepository, never()).save(any(Project.class));
    }

    @Test
    void failsWhenTheProjectIsGone() {
        Approval approval = approval();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bind(approval, "my-project"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("11");

        verifyNoInteractions(githubRepositoryPort, previewBranchPushService, auditRecorder);
    }

    @Test
    void pushFailurePropagatesSoTheWholeApprovalRollsBack() {
        // Same contract as ResultApprovalService#reflect: this runs inside approve()'s transaction,
        // so throwing here must undo the approval decision too — never leave an APPROVED row whose
        // work half-happened.
        Approval approval = approval();
        stubNotBoundProject("my-project");
        stubPreviewSessionAndUser();
        when(githubRepositoryPort.repositoryExists(1L, "octo/my-project")).thenReturn(true);
        Mockito.doThrow(new IllegalStateException("네트워크 오류"))
                .when(previewBranchPushService)
                .push(anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyString());

        assertThatThrownBy(() -> service.bind(approval, "my-project"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("네트워크 오류");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder, Mockito.atLeastOnce()).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditEvent::action)
                .doesNotContain(AuditAction.PREVIEW_BRANCH_PUSHED);
    }

    private void stubNotBoundProject(String name) {
        Project notBound = project(name, RepositoryBindingStatus.NOT_BOUND);
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(notBound));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubPreviewSessionAndUser() {
        stubPreviewSession();
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
    }

    private void stubPreviewSession() {
        when(previewSessionService.findByTaskId("task-1")).thenReturn(Optional.of(new PreviewSessionInfo(
                "session-1", 1L, 11L, 21L, "task-1", "container-1", 3000,
                "https://preview.qeploy.test/session-1/", LocalDateTime.now().plusMinutes(30)
        )));
    }

    private Project project(String name, RepositoryBindingStatus bindingStatus) {
        LocalDateTime now = LocalDateTime.now();
        boolean bound = bindingStatus == RepositoryBindingStatus.BOUND;
        return new Project(
                11L, 1L, name, ProjectStatus.ACTIVE, "vue", null, "fast", DeployStatus.DRAFT,
                null, null, bound ? "octo/repo" : null, bound ? "octo/repo" : null,
                RepositoryVisibility.PRIVATE, bindingStatus, RepositoryHealthStatus.UNKNOWN_ERROR,
                false, now, now
        );
    }

    private User activeUser() {
        return new User(1L, new GithubId("123"), "octo", null, 100L, "gh-token", "refresh-token",
                LocalDateTime.now().plusHours(1));
    }

    private Approval approval() {
        return new Approval(9L, 1L, 11L, 21L, "task-1", ApprovalType.REPOSITORY_BINDING,
                ApprovalStatus.PENDING, "[저장소 연결] my-project", LocalDateTime.now(), null);
    }
}
