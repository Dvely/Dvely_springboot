package com.example.dvely.deployment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.change.application.service.ResultApprovalService;
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
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class DeploymentCommandServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthCommandService authCommandService;
    @Mock private GithubPagesPort githubPagesPort;
    @Mock private GithubActionsPort githubActionsPort;
    @Mock private GithubRepoPort githubRepoPort;
    @Mock private DeploymentHistoryRepository deploymentHistoryRepository;
    @Mock private ProjectApprovalPolicyRepository policyRepository;
    @Mock private ResultApprovalService resultApprovalService;

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
                deploymentHistoryRepository,
                policyRepository,
                resultApprovalService
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
    void retry_createsNewHistoryLinkedToTheFailedOneAndCopiesTargetTypeAndVersion() {
        Project project = boundProject();
        DeploymentHistory failed = failedHistory(DeployTargetType.VERSION, "v3");
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(failed));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> persisted(invocation.getArgument(0), 52L));
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeployResult result = service.retryDeployment(1L, 51L);

        assertThat(result.deploymentId()).isEqualTo(52L);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.versionName()).isEqualTo("v3");
        assertThat(project.getDeployStatus()).isEqualTo(DeployStatus.PENDING);

        ArgumentCaptor<DeploymentHistory> captor = ArgumentCaptor.forClass(DeploymentHistory.class);
        verify(deploymentHistoryRepository).save(captor.capture());
        DeploymentHistory created = captor.getValue();
        assertThat(created.getDeployTargetType()).isEqualTo(DeployTargetType.VERSION);
        assertThat(created.getVersionLabel()).isEqualTo("v3");
        assertThat(created.getRetriedFromHistoryId()).isEqualTo(51L);
        assertThat(created.getCorrelationId()).isNotEqualTo(failed.getCorrelationId());
        assertThat(created.getTaskId()).isNull();
        verifyNoInteractions(userRepository, githubPagesPort, githubActionsPort, githubRepoPort);
    }

    @Test
    void retry_ofALatestTargetLeavesRequestedVersionNull() {
        Project project = boundProject();
        DeploymentHistory failed = failedHistory(DeployTargetType.LATEST, "v3");
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(failed));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> persisted(invocation.getArgument(0), 52L));
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.retryDeployment(1L, 51L);

        ArgumentCaptor<DeploymentHistory> captor = ArgumentCaptor.forClass(DeploymentHistory.class);
        verify(deploymentHistoryRepository).save(captor.capture());
        // LATEST retries re-resolve the current head commit at execute() time (worker), not the
        // version that was live when the original attempt failed — so no version is carried over.
        assertThat(captor.getValue().getVersionLabel()).isNull();
    }

    @Test
    void retry_rejectsWhenTargetIsNotFailed() {
        DeploymentHistory inProgress = new DeploymentHistory(
                51L, 1L, 11L, DeployTargetType.LATEST, "v3", null, DeployStatus.IN_PROGRESS, 901L,
                "correlation-51", null, null, null, null, null, null, null, null, null, null,
                1, 3, null, null, null, LocalDateTime.now(), LocalDateTime.now(), null
        );
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(inProgress));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(boundProject()));

        assertThatThrownBy(() -> service.retryDeployment(1L, 51L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IN_PROGRESS");
        verify(deploymentHistoryRepository, never()).save(any());
    }

    @Test
    void retry_rejectsWhenCallerDoesNotOwnTheProject() {
        DeploymentHistory failed = failedHistory(DeployTargetType.LATEST, null);
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(failed));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retryDeployment(2L, 51L))
                .isInstanceOf(com.example.dvely.project.domain.exception.ProjectNotFoundException.class);
        verify(deploymentHistoryRepository, never()).save(any());
    }

    @Test
    void retry_rejectsWhenHistoryDoesNotExist() {
        when(deploymentHistoryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retryDeployment(1L, 999L))
                .isInstanceOf(com.example.dvely.common.exception.NotFoundException.class);
    }

    private DeploymentHistory failedHistory(DeployTargetType targetType, String versionLabel) {
        LocalDateTime now = LocalDateTime.now();
        return new DeploymentHistory(
                51L, 1L, 11L, targetType, versionLabel, "https://octo.github.io/repo/",
                DeployStatus.FAILED, 901L, "correlation-51", null, null, null, null, null, null, null, null,
                null, "빌드 실패", 3, 3, null, null, null, now, now, null
        );
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
        // I45 F1: execute() now re-fetches the project fresh (via plain findById) immediately
        // before the deployment-status save, instead of reusing the snapshot read at the top of
        // execute() — see DeploymentCommandService#updateProjectDeploymentState.
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
        // Policy OFF here so this pre-existing (Track Z-unrelated) test keeps exercising the same
        // merge call it always did — see the dedicated mergeAllowed matrix tests below for the ON
        // behavior itself.
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L, true, true, true, true, false)));
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

    // ── I45 (#45) review follow-up F1: execute() re-fetches the project immediately before the
    // deployment-status save instead of reusing the snapshot read at the top of the method ──

    @Test
    void execute_savesTheFreshlyRefetchedProjectRatherThanTheSnapshotReadAtTheTopOfExecute() {
        Project staleSnapshot = boundProject(); // read at the top of execute(), used for GitHub calls
        Project freshFromDb = boundProject();   // what updateProjectDeploymentState() re-fetches
        DeploymentHistory history = claimedHistory();
        ReleaseMetadata metadata = new ReleaseMetadata(
                "abc123", "Release title", "Release body", "octo",
                "https://avatars.example/octo", 17, LocalDateTime.of(2026, 6, 10, 9, 30)
        );
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(history));
        when(deploymentHistoryRepository.findLatestByProjectId(11L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(staleSnapshot));
        // The self-inflicted race this fix closes: by the time execute() is ready to save the
        // deployment status, the row has already moved on (e.g. ensureWorkflow's own commit
        // triggered a webhook head-sync) — modeled here simply as "a different Project instance",
        // since the fix's whole point is that this call, not the stale snapshot, is what gets saved.
        when(projectRepository.findById(11L)).thenReturn(Optional.of(freshFromDb));
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L, true, true, true, true, false)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepoPort.detectPackageManager("user-token", "octo/repo")).thenReturn(PackageManager.NPM);
        when(githubRepoPort.detectNodeVersion("user-token", "octo/repo")).thenReturn("20");
        when(githubRepoPort.detectFrameworkType("user-token", "octo/repo")).thenReturn("vue");
        when(githubRepoPort.hasNewCommits("user-token", "octo/repo", "main", "preview")).thenReturn(false);
        when(githubRepoPort.getHeadCommitSha("user-token", "octo/repo", "main")).thenReturn("abc123");
        when(githubRepoPort.findSequentialTagForCommit("user-token", "octo/repo", "abc123")).thenReturn("v7");
        when(githubRepoPort.getReleaseMetadata("user-token", "octo/repo", "abc123", null)).thenReturn(metadata);
        when(githubPagesPort.getPages("user-token", "octo/repo")).thenReturn(
                new GithubPagesPort.PagesInfo(true, "https://octo.github.io/repo/", "gh-pages", "site.example.com"));
        when(githubActionsPort.findWorkflowRun(
                "user-token", "octo/repo", "qeploy-deploy.yml", "correlation-51", "abc123", history.getTriggeredAt()
        )).thenReturn(new WorkflowRunMatch(901L, "abc123", "queued", null));
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.execute(51L);

        ArgumentCaptor<Project> savedCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).isSameAs(freshFromDb);
        assertThat(savedCaptor.getValue()).isNotSameAs(staleSnapshot);
        assertThat(freshFromDb.getDeployStatus()).isEqualTo(DeployStatus.IN_PROGRESS);
    }

    @Test
    void execute_skipsProjectStatusSaveWithoutFailingWhenProjectWasDeletedConcurrently() {
        Project staleSnapshot = boundProject();
        DeploymentHistory history = claimedHistory();
        ReleaseMetadata metadata = new ReleaseMetadata(
                "abc123", "Release title", "Release body", "octo",
                "https://avatars.example/octo", 17, LocalDateTime.of(2026, 6, 10, 9, 30)
        );
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(history));
        when(deploymentHistoryRepository.findLatestByProjectId(11L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(staleSnapshot));
        // The row is gone by the time we go to save the deployment status — a genuine concurrent
        // delete, not just a version conflict.
        when(projectRepository.findById(11L)).thenReturn(Optional.empty());
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L, true, true, true, true, false)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepoPort.detectPackageManager("user-token", "octo/repo")).thenReturn(PackageManager.NPM);
        when(githubRepoPort.detectNodeVersion("user-token", "octo/repo")).thenReturn("20");
        when(githubRepoPort.detectFrameworkType("user-token", "octo/repo")).thenReturn("vue");
        when(githubRepoPort.hasNewCommits("user-token", "octo/repo", "main", "preview")).thenReturn(false);
        when(githubRepoPort.getHeadCommitSha("user-token", "octo/repo", "main")).thenReturn("abc123");
        when(githubRepoPort.findSequentialTagForCommit("user-token", "octo/repo", "abc123")).thenReturn("v7");
        when(githubRepoPort.getReleaseMetadata("user-token", "octo/repo", "abc123", null)).thenReturn(metadata);
        when(githubPagesPort.getPages("user-token", "octo/repo")).thenReturn(
                new GithubPagesPort.PagesInfo(true, "https://octo.github.io/repo/", "gh-pages", "site.example.com"));
        when(githubActionsPort.findWorkflowRun(
                "user-token", "octo/repo", "qeploy-deploy.yml", "correlation-51", "abc123", history.getTriggeredAt()
        )).thenReturn(new WorkflowRunMatch(901L, "abc123", "queued", null));
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.execute(51L); // must not throw

        verify(projectRepository, never()).save(any());
        assertThat(history.getStatus()).isEqualTo(DeployStatus.IN_PROGRESS);
    }

    // ── Track Z (#56) D1/§5.4: prepareRelease's mergeAllowed matrix. None of the pre-existing
    // execute_* tests above ever exercised hasNewCommits=true (they all stub it false), so this
    // also closes a pre-existing gap in "does a direct deploy merge at all" coverage. ──

    @Test
    void prepareRelease_policyOnAndAlreadyReleasedProjectSkipsAutomaticMergeWithoutEvenCheckingForNewCommits() {
        Project project = boundProject(); // currentVersion = "v6" -> already released once
        DeploymentHistory history = claimedHistory();
        ReleaseMetadata metadata = new ReleaseMetadata(
                "abc123", "Release title", "Release body", "octo",
                "https://avatars.example/octo", null, LocalDateTime.of(2026, 6, 10, 9, 30)
        );
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(history));
        when(deploymentHistoryRepository.findLatestByProjectId(11L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
        // Policy ON via the fail-safe default (no policy row yet) — D1 must still block this
        // direct deploy's merge for an already-established project.
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepoPort.detectPackageManager("user-token", "octo/repo")).thenReturn(PackageManager.NPM);
        when(githubRepoPort.detectNodeVersion("user-token", "octo/repo")).thenReturn("20");
        when(githubRepoPort.detectFrameworkType("user-token", "octo/repo")).thenReturn("vue");
        when(githubRepoPort.getHeadCommitSha("user-token", "octo/repo", "main")).thenReturn("abc123");
        when(githubRepoPort.findSequentialTagForCommit("user-token", "octo/repo", "abc123")).thenReturn("v7");
        when(githubRepoPort.getReleaseMetadata("user-token", "octo/repo", "abc123", null)).thenReturn(metadata);
        when(githubPagesPort.getPages("user-token", "octo/repo")).thenReturn(
                new GithubPagesPort.PagesInfo(true, "https://octo.github.io/repo/", "gh-pages", "site.example.com"));
        when(githubActionsPort.findWorkflowRun(
                "user-token", "octo/repo", "qeploy-deploy.yml", "correlation-51", "abc123", history.getTriggeredAt()
        )).thenReturn(new WorkflowRunMatch(901L, "abc123", "queued", null));
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.execute(51L);

        // mergeAllowed is computed (and false) before the `&&` short-circuits past hasNewCommits
        // — a blocked project doesn't even pay for the extra GitHub compare call.
        verify(githubRepoPort, never()).hasNewCommits(anyString(), anyString(), anyString(), anyString());
        verify(githubRepoPort, never()).createOrGetPullRequest(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(githubRepoPort, never()).mergePullRequest(anyString(), anyString(), anyInt());
        // main's head is still resolved and tagged as-is — a direct deploy still publishes
        // whatever IS on main, it just no longer drags preview onto it.
        assertThat(history.getVersionLabel()).isEqualTo("v7");
        assertThat(history.getCommitSha()).isEqualTo("abc123");
    }

    @Test
    void prepareRelease_policyOffMergesDespiteAlreadyBeingReleased() {
        Project project = boundProject(); // currentVersion = "v6" -> already released once
        DeploymentHistory history = claimedHistory();
        ReleaseMetadata metadata = new ReleaseMetadata(
                "merged-sha", "Release title", "Release body", "octo",
                "https://avatars.example/octo", 99, LocalDateTime.of(2026, 6, 10, 9, 30)
        );
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(history));
        when(deploymentHistoryRepository.findLatestByProjectId(11L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L, true, true, true, true, false)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepoPort.detectPackageManager("user-token", "octo/repo")).thenReturn(PackageManager.NPM);
        when(githubRepoPort.detectNodeVersion("user-token", "octo/repo")).thenReturn("20");
        when(githubRepoPort.detectFrameworkType("user-token", "octo/repo")).thenReturn("vue");
        when(githubRepoPort.hasNewCommits("user-token", "octo/repo", "main", "preview")).thenReturn(true);
        when(githubRepoPort.createOrGetPullRequest(
                "user-token", "octo/repo", "preview", "main", "[Qeploy] Deploy preview to main"
        )).thenReturn(99);
        when(githubRepoPort.mergePullRequest("user-token", "octo/repo", 99)).thenReturn("merged-sha");
        when(githubRepoPort.getHeadCommitSha("user-token", "octo/repo", "main")).thenReturn("merged-sha");
        when(githubRepoPort.findSequentialTagForCommit("user-token", "octo/repo", "merged-sha")).thenReturn("v7");
        when(githubRepoPort.getReleaseMetadata("user-token", "octo/repo", "merged-sha", 99)).thenReturn(metadata);
        when(githubPagesPort.getPages("user-token", "octo/repo")).thenReturn(
                new GithubPagesPort.PagesInfo(true, "https://octo.github.io/repo/", "gh-pages", "site.example.com"));
        when(githubActionsPort.findWorkflowRun(
                "user-token", "octo/repo", "qeploy-deploy.yml", "correlation-51", "merged-sha", history.getTriggeredAt()
        )).thenReturn(new WorkflowRunMatch(901L, "merged-sha", "queued", null));
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.execute(51L);

        verify(githubRepoPort).createOrGetPullRequest(
                "user-token", "octo/repo", "preview", "main", "[Qeploy] Deploy preview to main");
        verify(githubRepoPort).mergePullRequest("user-token", "octo/repo", 99);
        assertThat(history.getCommitSha()).isEqualTo("merged-sha");
    }

    @Test
    void prepareRelease_policyOnButNeverReleasedBeforeStillMergesForTheFirstPublish() {
        Project project = neverReleasedBoundProject(); // currentVersion = null -> never released
        DeploymentHistory history = claimedHistory();
        ReleaseMetadata metadata = new ReleaseMetadata(
                "merged-sha", "Release title", "Release body", "octo",
                "https://avatars.example/octo", 99, LocalDateTime.of(2026, 6, 10, 9, 30)
        );
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(history));
        when(deploymentHistoryRepository.findLatestByProjectId(11L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
        // Policy ON (fail-safe default) — but this is the project's very first release, so D9's
        // "must already be BOUND" gate never had a chance to fire for it; prepareRelease is the
        // only thing that will ever get preview's content onto main.
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        // ...and this project never went through a RESULT-gate decision either (no REJECTED/MERGED
        // Change row yet) — the "never released" carve-out only applies when BOTH facts hold
        // (BLOCKING-1 fix). See the dedicated test below for the case where it does NOT hold.
        when(resultApprovalService.hasResultGateHistory(11L)).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepoPort.detectPackageManager("user-token", "octo/repo")).thenReturn(PackageManager.NPM);
        when(githubRepoPort.detectNodeVersion("user-token", "octo/repo")).thenReturn("20");
        when(githubRepoPort.detectFrameworkType("user-token", "octo/repo")).thenReturn("vue");
        when(githubRepoPort.hasNewCommits("user-token", "octo/repo", "main", "preview")).thenReturn(true);
        when(githubRepoPort.createOrGetPullRequest(
                "user-token", "octo/repo", "preview", "main", "[Qeploy] Deploy preview to main"
        )).thenReturn(99);
        when(githubRepoPort.mergePullRequest("user-token", "octo/repo", 99)).thenReturn("merged-sha");
        when(githubRepoPort.getHeadCommitSha("user-token", "octo/repo", "main")).thenReturn("merged-sha");
        when(githubRepoPort.findSequentialTagForCommit("user-token", "octo/repo", "merged-sha")).thenReturn(null);
        when(githubRepoPort.createNextSequentialTag("user-token", "octo/repo", "merged-sha")).thenReturn("v1");
        when(githubRepoPort.getReleaseMetadata("user-token", "octo/repo", "merged-sha", 99)).thenReturn(metadata);
        when(githubPagesPort.getPages("user-token", "octo/repo")).thenReturn(
                new GithubPagesPort.PagesInfo(true, "https://octo.github.io/repo/", "gh-pages", "site.example.com"));
        when(githubActionsPort.findWorkflowRun(
                "user-token", "octo/repo", "qeploy-deploy.yml", "correlation-51", "merged-sha", history.getTriggeredAt()
        )).thenReturn(new WorkflowRunMatch(901L, "merged-sha", "queued", null));
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.execute(51L);

        verify(githubRepoPort).createOrGetPullRequest(
                "user-token", "octo/repo", "preview", "main", "[Qeploy] Deploy preview to main");
        verify(githubRepoPort).mergePullRequest("user-token", "octo/repo", 99);
        assertThat(history.getVersionLabel()).isEqualTo("v1");
    }

    // ── Review follow-up (BLOCKING-1): the "never released yet" merge carve-out must NOT also
    // cover a project that already has a REJECTED RESULT-gate decision sitting in preview — that
    // decision is exactly what the "never released before" test above did not model. This is the
    // deterministic (no concurrency needed) repro from the review: BOUND, never deployed
    // (currentVersion == null), but the RESULT gate already rejected once. ──

    @Test
    void prepareRelease_neverReleasedButAlreadyRejectedByResultGateSkipsAutomaticMergeOfTheRejectedContent() {
        Project project = neverReleasedBoundProject(); // currentVersion == null -> never released
        DeploymentHistory history = claimedHistory();
        ReleaseMetadata metadata = new ReleaseMetadata(
                "abc123", "Release title", "Release body", "octo",
                "https://avatars.example/octo", null, LocalDateTime.of(2026, 6, 10, 9, 30)
        );
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(history));
        when(deploymentHistoryRepository.findLatestByProjectId(11L)).thenReturn(Optional.of(history));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
        // Policy ON (fail-safe default).
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        // The crux of BLOCKING-1: this project already had a RESULT decision (REJECTED) even
        // though it was never actually deployed — hasResultGateHistory must be what disqualifies
        // it from the "first release" exception, not project.getCurrentVersion().
        when(resultApprovalService.hasResultGateHistory(11L)).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepoPort.detectPackageManager("user-token", "octo/repo")).thenReturn(PackageManager.NPM);
        when(githubRepoPort.detectNodeVersion("user-token", "octo/repo")).thenReturn("20");
        when(githubRepoPort.detectFrameworkType("user-token", "octo/repo")).thenReturn("vue");
        when(githubRepoPort.getHeadCommitSha("user-token", "octo/repo", "main")).thenReturn("abc123");
        when(githubRepoPort.findSequentialTagForCommit("user-token", "octo/repo", "abc123")).thenReturn("v7");
        when(githubRepoPort.getReleaseMetadata("user-token", "octo/repo", "abc123", null)).thenReturn(metadata);
        when(githubPagesPort.getPages("user-token", "octo/repo")).thenReturn(
                new GithubPagesPort.PagesInfo(true, "https://octo.github.io/repo/", "gh-pages", "site.example.com"));
        when(githubActionsPort.findWorkflowRun(
                "user-token", "octo/repo", "qeploy-deploy.yml", "correlation-51", "abc123", history.getTriggeredAt()
        )).thenReturn(new WorkflowRunMatch(901L, "abc123", "queued", null));
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.execute(51L);

        // The rejected content must never be merged by this direct deploy — merge is the RESULT
        // approval flow's job now, and this project has already been through it once (and was
        // rejected). A direct deploy just publishes whatever main already has.
        verify(githubRepoPort, never()).hasNewCommits(anyString(), anyString(), anyString(), anyString());
        verify(githubRepoPort, never()).createOrGetPullRequest(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(githubRepoPort, never()).mergePullRequest(anyString(), anyString(), anyInt());
        assertThat(history.getVersionLabel()).isEqualTo("v7");
        assertThat(history.getCommitSha()).isEqualTo("abc123");
    }

    // ── I45 (#45) review follow-up F2: handleExecutionFailure swallows (logs, does not
    // propagate) an OOLFE from its own best-effort project-status mirror ──

    @Test
    void executeQueued_swallowsOptimisticLockingFailureFromProjectStatusMirrorAfterAnExecutionFailure() {
        Project notBound = new Project(
                11L, 1L, "my-project", ProjectStatus.ACTIVE, "vue", null, "fast",
                DeployStatus.LIVE, "https://octo.github.io/repo/", "v6", null, null,
                RepositoryVisibility.PUBLIC, RepositoryBindingStatus.NOT_BOUND, RepositoryHealthStatus.HEALTHY,
                false, LocalDateTime.now(), LocalDateTime.now()
        );
        // attempt already at maxAttempts so history.retry(...) inside handleExecutionFailure
        // goes straight to fail() (single call, deterministic — no need to simulate 3 rounds).
        DeploymentHistory exhaustedHistory = new DeploymentHistory(
                51L, 1L, 11L, DeployTargetType.LATEST, null, null, DeployStatus.IN_PROGRESS, null,
                "correlation-51", null, null, null, null, null, null, null, null, "task-51", null,
                3, 3, null, "worker-1", LocalDateTime.now().plusMinutes(2), LocalDateTime.now(), LocalDateTime.now(), null
        );
        // First findById (top of execute()) resolves the history and drives it to FAILED inside
        // handleExecutionFailure's own second findById call.
        when(deploymentHistoryRepository.findById(51L)).thenReturn(Optional.of(exhaustedHistory));
        when(deploymentHistoryRepository.findLatestByProjectId(11L)).thenReturn(Optional.of(exhaustedHistory));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(notBound));
        when(deploymentHistoryRepository.save(any(DeploymentHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // handleExecutionFailure's best-effort project mirror: found, but saving it loses a
        // version race.
        when(projectRepository.findById(11L)).thenReturn(Optional.of(notBound));
        when(projectRepository.save(notBound)).thenThrow(
                new ObjectOptimisticLockingFailureException(Project.class, 11L));

        // resolveProject() rejects a NOT_BOUND project with ForbiddenException — that's execute()'s
        // failure, which executeQueued's catch(Exception) routes into handleExecutionFailure.
        service.executeQueued(51L); // must not throw despite the project save's OOLFE

        assertThat(exhaustedHistory.getStatus()).isEqualTo(DeployStatus.FAILED);
        verify(deploymentHistoryRepository).save(exhaustedHistory);
        verify(projectRepository).save(notBound);
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
                source.getUpdatedAt(),
                source.getRetriedFromHistoryId()
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
                now,
                null
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

    private Project neverReleasedBoundProject() {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L,
                1L,
                "my-project",
                ProjectStatus.ACTIVE,
                "vue",
                null,
                "fast",
                DeployStatus.PENDING,
                null,
                null, // currentVersion == null -> this project has never completed a release
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
