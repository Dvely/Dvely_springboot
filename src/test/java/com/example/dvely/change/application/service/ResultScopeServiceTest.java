package com.example.dvely.change.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.service.ServerPreviewBranch;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.approval.domain.value.RejectOption;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.change.application.port.out.GithubComparePort;
import com.example.dvely.change.application.port.out.GithubComparePort.Commit;
import com.example.dvely.change.application.port.out.GithubComparePort.Comparison;
import com.example.dvely.change.application.port.out.GithubComparePort.File;
import com.example.dvely.change.application.result.ResultScope;
import com.example.dvely.change.application.result.ResultScope.Origin;
import com.example.dvely.change.infrastructure.persistence.entity.ChangeEntity;
import com.example.dvely.change.infrastructure.persistence.entity.ServerPushedCommitEntity;
import com.example.dvely.change.infrastructure.persistence.repository.SpringDataChangeRepository;
import com.example.dvely.change.infrastructure.persistence.repository.SpringDataChangeRepository.TaskDecisionView;
import com.example.dvely.common.exception.GithubAppReauthorizationRequiredException;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResultScopeServiceTest {

    private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
    private final SpringDataChangeRepository changeRepository = mock(SpringDataChangeRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final GithubComparePort compare = mock(GithubComparePort.class);
    private final ServerPreviewBranch serverPreviewBranch = mock(ServerPreviewBranch.class);
    private final ServerPushLedger ledger = mock(ServerPushLedger.class);
    private final List<ServerPushedCommitEntity> recorded = new ArrayList<>();

    private final ResultScopeService service = new ResultScopeService(
            approvalRepository, changeRepository, projectRepository, userRepository, mock(AuthCommandService.class),
            compare, serverPreviewBranch, ledger, new ObjectMapper().findAndRegisterModules());

    @BeforeEach
    void setUp() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project()));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(serverPreviewBranch.enabled()).thenReturn(true);
        when(changeRepository.findByTaskId("task-1")).thenReturn(Optional.of(change()));
        // 이번 작업의 커밋은 서버가 push 하며 남긴 기록이 있다
        recorded.add(new ServerPushedCommitEntity("c1", "octo/repo", "task-1", ServerPushedCommitEntity.TASK));
        when(ledger.find(any(), any())).thenReturn(recorded);
    }

    @Test
    void onlyThisTasksWorkNeedsNoPerCommitLookup() {
        pending();
        when(compare.compare("gh-token", "octo/repo", "main", "preview")).thenReturn(new Comparison(
                List.of(commit("c1", "feat: apply Qeploy Agent task task-1")), 1,
                List.of(new File("README.md", "modified", 1, 0, null), new File("logo.png", "added", null, null, null))));

        ResultScope result = service.view(1L, 9L);

        assertThat(result.scopeState()).isEqualTo("READY");
        assertThat(result.scope().includesOtherWork()).isFalse();
        assertThat(result.scope().files()).allSatisfy(f -> assertThat(f.origins()).containsExactly(Origin.THIS_TASK));
        assertThat(result.scope().totals()).isEqualTo(new ResultScope.Totals(2, 1, 0));
        assertThat(result.notice()).isEqualTo("승인하면 이번 작업의 변경이 main 에 반영됩니다: 파일 2개(+1 −0).");
        assertThat(result.rejectOptions()).extracting(ResultScope.RejectOptionView::option).containsExactly("KEEP", "REVERT");
        verify(compare, never()).commitFiles(anyString(), anyString(), anyString());
    }

    @Test
    void mixedOriginsAreResolvedPerFileInDeclarationOrder() {
        pending();
        when(compare.compare(any(), any(), any(), any())).thenReturn(new Comparison(List.of(
                commit("r1", "feat: apply Qeploy Agent task task-0"),
                commit("c1", "feat: apply Qeploy Agent task task-1"),
                commit("h1", "fix typo by hand")), 3,
                List.of(new File("styles.css", "modified", 12, 3, null), new File("README.md", "modified", 1, 0, null))));
        when(changeRepository.findTaskDecisions(any(), any())).thenReturn(List.of(decision("task-0", "REJECTED", 5L)));
        when(compare.commitFiles("gh-token", "octo/repo", "r1")).thenReturn(List.of("styles.css"));
        when(compare.commitFiles("gh-token", "octo/repo", "c1")).thenReturn(List.of("styles.css", "README.md"));
        when(compare.commitFiles("gh-token", "octo/repo", "h1")).thenReturn(List.of());

        ResultScope.Scope scope = service.view(1L, 9L).scope();

        assertThat(scope.files().get(0).origins()).containsExactly(Origin.THIS_TASK, Origin.PREVIOUSLY_REJECTED);
        assertThat(scope.files().get(1).origins()).containsExactly(Origin.THIS_TASK);
        assertThat(scope.commits()).extracting(ResultScope.CommitView::origin)
                .containsExactly(Origin.PREVIOUSLY_REJECTED, Origin.THIS_TASK, Origin.UNKNOWN);
        assertThat(scope.commits().get(0).decision()).isEqualTo(new ResultScope.Decision(5L, "REJECTED"));
        assertThat(scope.includesOtherWork()).isTrue();
        assertThat(scope.includesRejected()).isTrue();
        assertThat(ResultScopeService.notice(scope)).isEqualTo(
                "승인하면 이번 작업 외의 변경도 함께 main 에 반영됩니다: 파일 2개(+13 −3). 그중 1건은 이전에 거절한 변경입니다.");
        assertThat(ResultScopeService.revertDescription(scope)).contains("다른 변경 2건");
    }

    @Test
    void aTruncatedComparisonHasNoTotalsAndNoNumbersInTheNotice() {
        pending();
        List<File> files = new ArrayList<>();
        for (int i = 0; i < ResultScopeService.GITHUB_FILE_LIMIT; i++) {
            files.add(new File("f" + i, "added", 1, 0, null));
        }
        when(compare.compare(any(), any(), any(), any())).thenReturn(new Comparison(
                List.of(commit("c1", "feat: apply Qeploy Agent task task-1")), 1, files));

        ResultScope result = service.view(1L, 9L);

        assertThat(result.scope().truncated()).isTrue();
        assertThat(result.scope().totals()).isNull();
        assertThat(result.notice()).isEqualTo("변경 파일이 많아 일부만 표시합니다. 이번 작업 외의 변경이 함께 반영될 수 있습니다.")
                .doesNotContainPattern("\\d");
    }

    @Test
    void commitsBehindARevertAreNotCountedAsIncluded() {
        pending();
        recorded.add(new ServerPushedCommitEntity("rv", "octo/repo", "task-0", ServerPushedCommitEntity.REVERT));
        when(compare.compare(any(), any(), any(), any())).thenReturn(new Comparison(List.of(
                commit("r1", "feat: apply Qeploy Agent task task-0"),
                new Commit("rv", "revert: preview 를 main 상태로 되돌림 (approval 5)", List.of("r1", "m0")),
                commit("c1", "feat: apply Qeploy Agent task task-1", "rv")), 3,
                List.of(new File("README.md", "modified", 1, 0, null))));

        ResultScope.Scope scope = service.view(1L, 9L).scope();

        assertThat(scope.commits()).extracting(ResultScope.CommitView::sha).containsExactly("c1");
        assertThat(scope.includesRejected()).isFalse();
        assertThat(scope.includesOtherWork()).isFalse();
    }

    @Test
    void aMessageClaimingThisTaskIsNotTrustedWithoutTheServersRecord() {
        pending();
        when(compare.compare(any(), any(), any(), any())).thenReturn(new Comparison(List.of(
                commit("c1", "feat: apply Qeploy Agent task task-1"),
                commit("x1", "feat: apply Qeploy Agent task task-1")), 2,
                List.of(new File("README.md", "modified", 1, 0, null))));
        when(compare.commitFiles(any(), any(), any())).thenReturn(List.of("README.md"));

        ResultScope.Scope scope = service.view(1L, 9L).scope();

        assertThat(scope.commits()).extracting(ResultScope.CommitView::origin)
                .containsExactly(Origin.THIS_TASK, Origin.UNKNOWN);
        assertThat(scope.commits().get(1).taskId()).isNull();
        assertThat(scope.includesOtherWork()).isTrue();
    }

    @Test
    void aCommitThatOnlyLooksLikeARevertHidesNothing() {
        pending();
        when(compare.compare(any(), any(), any(), any())).thenReturn(new Comparison(List.of(
                commit("h1", "add tracking script by hand"),
                new Commit("rv", "revert: preview 를 main 상태로 되돌림 (approval 5)", List.of("h1", "m0")),
                commit("c1", "feat: apply Qeploy Agent task task-1", "rv")), 3,
                List.of(new File("tracker.js", "added", 3, 0, null))));
        when(compare.commitFiles(any(), any(), any())).thenReturn(List.of());

        ResultScope.Scope scope = service.view(1L, 9L).scope();

        assertThat(scope.commits()).extracting(ResultScope.CommitView::sha).containsExactly("h1", "rv", "c1");
        assertThat(scope.includesOtherWork()).isTrue();
    }

    @Test
    void aDeferredPushAddsThisTasksServerDiff() {
        pending();
        ChangeEntity change = change();
        change.updateFromServerSnapshot("요약", """
                diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
                new file mode 100644
                --- /dev/null
                +++ b/.github/workflows/ci.yml
                @@ -0,0 +1,2 @@
                +name: ci
                +on: push
                """, "0123456789abcdef0123456789abcdef01234567");
        change.deferPush("0123456789abcdef0123456789abcdef01234567");
        when(changeRepository.findByTaskId("task-1")).thenReturn(Optional.of(change));
        when(compare.compare(any(), any(), any(), any())).thenReturn(Comparison.empty());

        ResultScope.Scope scope = service.view(1L, 9L).scope();

        assertThat(scope.pendingPush()).isTrue();
        assertThat(scope.files()).singleElement().satisfies(f -> {
            assertThat(f.path()).isEqualTo(".github/workflows/ci.yml");
            assertThat(f.status()).isEqualTo("added");
            assertThat(f.additions()).isEqualTo(2);
            assertThat(f.origins()).containsExactly(Origin.THIS_TASK);
        });
        assertThat(scope.totals()).isEqualTo(new ResultScope.Totals(1, 2, 0));
    }

    @Test
    void aFailedComparisonIsReportedAsUnavailableWithOptionsStillOffered() {
        pending();
        when(compare.compare(any(), any(), any(), any())).thenThrow(new IllegalStateException("브랜치 비교 실패"));

        ResultScope result = service.view(1L, 9L);

        assertThat(result.scopeState()).isEqualTo("UNAVAILABLE");
        assertThat(result.scope()).isNull();
        assertThat(result.notice()).isEqualTo(ResultScopeService.UNAVAILABLE_NOTICE);
        assertThat(result.rejectOptions()).hasSize(2);
    }

    @Test
    void reauthorizationIsNotHiddenAsUnavailable() {
        pending();
        when(compare.compare(any(), any(), any(), any())).thenThrow(new GithubAppReauthorizationRequiredException());

        assertThatThrownBy(() -> service.view(1L, 9L)).isInstanceOf(GithubAppReauthorizationRequiredException.class);
    }

    @Test
    void revertIsOfferedButDisabledWhereServerSideGitIsOff() {
        pending();
        when(serverPreviewBranch.enabled()).thenReturn(false);
        when(compare.compare(any(), any(), any(), any())).thenReturn(Comparison.empty());

        ResultScope.RejectOptionView revert = service.view(1L, 9L).rejectOptions().get(1);

        assertThat(revert.available()).isFalse();
        assertThat(revert.unavailableReason()).isEqualTo(ResultScopeService.REVERT_UNAVAILABLE_REASON);
    }

    @Test
    void aDecidedApprovalShowsTheScopeRecordedAtDecisionTime() {
        Approval pending = approval(ApprovalStatus.PENDING, null);
        when(compare.compare(any(), any(), any(), any())).thenReturn(new Comparison(
                List.of(commit("c1", "feat: apply Qeploy Agent task task-1")), 1,
                List.of(new File("README.md", "renamed", 1, 0, "OLD.md"))));
        String recorded = service.snapshotJson(pending);

        ChangeEntity change = change();
        change.markRejected(9L);
        change.recordDecidedScope(recorded);
        when(changeRepository.findByTaskId("task-1")).thenReturn(Optional.of(change));
        Approval rejected = approval(ApprovalStatus.REJECTED, RejectOption.REVERT);
        when(approvalRepository.findByIdAndOwnerUserId(9L, 1L)).thenReturn(Optional.of(rejected));

        ResultScope result = service.view(1L, 9L);

        assertThat(result.scopeState()).isEqualTo("DECIDED");
        assertThat(result.scope()).isNull();
        assertThat(result.rejectOptions()).isEmpty();
        assertThat(result.decidedSnapshot().option()).isEqualTo("REVERT");
        assertThat(result.decidedSnapshot().scope().files()).singleElement()
                .satisfies(f -> assertThat(f.previousPath()).isEqualTo("OLD.md"));
        assertThat(result.notice()).isEqualTo(result.decidedSnapshot().notice())
                .isEqualTo("거절하고 preview 를 main 과 같은 상태로 되돌렸습니다.");
    }

    @Test
    void aCancelledApprovalHasNoScopeAndSaysSo() {
        when(approvalRepository.findByIdAndOwnerUserId(9L, 1L))
                .thenReturn(Optional.of(approval(ApprovalStatus.CANCELLED, null)));

        ResultScope result = service.view(1L, 9L);

        assertThat(result.decidedSnapshot().scope()).isNull();
        assertThat(result.decidedSnapshot().option()).isNull();
        assertThat(result.notice()).isEqualTo("결정하기 전에 작업이 취소되었습니다.");
        verify(compare, never()).compare(any(), any(), any(), any());
    }

    @Test
    void onlyResultApprovalsHaveAScope() {
        when(approvalRepository.findByIdAndOwnerUserId(9L, 1L)).thenReturn(Optional.of(new Approval(
                9L, 1L, 11L, 21L, "task-1", ApprovalType.DEPLOYMENT, ApprovalStatus.PENDING, "배포", LocalDateTime.now(), null)));

        assertThatThrownBy(() -> service.view(1L, 9L)).isInstanceOf(IllegalArgumentException.class);
    }

    private void pending() {
        when(approvalRepository.findByIdAndOwnerUserId(9L, 1L))
                .thenReturn(Optional.of(approval(ApprovalStatus.PENDING, null)));
    }

    private static Approval approval(ApprovalStatus status, RejectOption option) {
        return new Approval(9L, 1L, 11L, 21L, "task-1", ApprovalType.RESULT, status, "[결과 반영] 요약",
                LocalDateTime.now(), status == ApprovalStatus.PENDING ? null : LocalDateTime.now(), option);
    }

    private static Commit commit(String sha, String message, String... parents) {
        return new Commit(sha, message, parents.length == 0 ? List.of("p-" + sha) : List.of(parents));
    }

    private static TaskDecisionView decision(String taskId, String status, Long approvalId) {
        return new TaskDecisionView() {
            public String getTaskId() { return taskId; }
            public String getStatus() { return status; }
            public Long getApprovalId() { return approvalId; }
        };
    }

    private static ChangeEntity change() {
        return new ChangeEntity(1L, 11L, 21L, "task-1", "session-1", "요약", "");
    }

    private static Project project() {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L, 1L, "my-project", ProjectStatus.ACTIVE, "vue", null, "fast", DeployStatus.LIVE,
                null, "v3", "octo/repo", "octo/repo", RepositoryVisibility.PUBLIC,
                RepositoryBindingStatus.BOUND, RepositoryHealthStatus.HEALTHY, false, now, now
        );
    }

    private static User user() {
        return new User(1L, new GithubId("123"), "octo", null, 100L, "gh-token", "refresh-token",
                LocalDateTime.now().plusHours(1));
    }
}
