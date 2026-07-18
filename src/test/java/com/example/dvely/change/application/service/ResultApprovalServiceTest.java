package com.example.dvely.change.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.change.domain.value.ChangeStatus;
import com.example.dvely.change.infrastructure.persistence.entity.ChangeEntity;
import com.example.dvely.change.infrastructure.persistence.repository.SpringDataChangeRepository;
import com.example.dvely.deployment.application.port.out.GithubRepoPort;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResultApprovalServiceTest {

    private final SpringDataChangeRepository changeRepository = mock(SpringDataChangeRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthCommandService authCommandService = mock(AuthCommandService.class);
    private final GithubRepoPort githubRepoPort = mock(GithubRepoPort.class);

    private final ResultApprovalService service = new ResultApprovalService(
            changeRepository, projectRepository, userRepository, authCommandService, githubRepoPort
    );

    @Test
    void reflectCreatesAndMergesAPullRequestWhenPreviewHasNewCommits() {
        ChangeEntity change = changeEntity();
        when(changeRepository.findByTaskId("task-1")).thenReturn(Optional.of(change));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(boundProject()));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepoPort.hasNewCommits("gh-token", "octo/repo", "main", "preview")).thenReturn(true);
        when(githubRepoPort.createOrGetPullRequest(
                "gh-token", "octo/repo", "preview", "main", "[Qeploy] Apply approved result to main"
        )).thenReturn(77);
        when(githubRepoPort.mergePullRequest("gh-token", "octo/repo", 77)).thenReturn("merged-sha-123");
        when(changeRepository.save(any(ChangeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResultApprovalService.ReflectResult result = service.reflect(approval(9L));

        assertThat(result.prNumber()).isEqualTo(77);
        assertThat(result.mergeCommitSha()).isEqualTo("merged-sha-123");
        assertThat(change.getStatus()).isEqualTo(ChangeStatus.MERGED.name());
        assertThat(change.getApprovalId()).isEqualTo(9L);
        assertThat(change.getPrNumber()).isEqualTo(77);
        assertThat(change.getMergeCommitSha()).isEqualTo("merged-sha-123");
        assertThat(change.getMergedAt()).isNotNull();
        verify(changeRepository).save(change);
    }

    @Test
    void reflectIsIdempotentWhenPreviewHasNoNewCommits() {
        // D8: already merged by a prior partially-failed approve, or an empty diff — either way
        // there is nothing to merge, so no PR/merge call happens and main's current head is used.
        ChangeEntity change = changeEntity();
        when(changeRepository.findByTaskId("task-1")).thenReturn(Optional.of(change));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(boundProject()));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepoPort.hasNewCommits("gh-token", "octo/repo", "main", "preview")).thenReturn(false);
        when(githubRepoPort.getHeadCommitSha("gh-token", "octo/repo", "main")).thenReturn("head-sha-999");
        when(changeRepository.save(any(ChangeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResultApprovalService.ReflectResult result = service.reflect(approval(9L));

        assertThat(result.prNumber()).isNull();
        assertThat(result.mergeCommitSha()).isEqualTo("head-sha-999");
        assertThat(change.getPrNumber()).isNull();
        assertThat(change.getStatus()).isEqualTo(ChangeStatus.MERGED.name());
        verify(githubRepoPort, never()).createOrGetPullRequest(any(), any(), any(), any(), any());
        verify(githubRepoPort, never()).mergePullRequest(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void reflectThrowsWhenNoChangeIsLinkedToTheTask() {
        // E-RA-05: the gate always records the Change before creating the RESULT approval
        // (§5.2) — a missing row here means the gate's own contract was violated.
        when(changeRepository.findByTaskId("task-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reflect(approval(9L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Change");
        verify(changeRepository, never()).save(any());
    }

    @Test
    void reflectThrowsWhenProjectCannotBeResolved() {
        when(changeRepository.findByTaskId("task-1")).thenReturn(Optional.of(changeEntity()));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reflect(approval(9L)))
                .isInstanceOf(IllegalStateException.class);
        verify(changeRepository, never()).save(any());
    }

    @Test
    void reflectPropagatesGithubFailureWithoutMarkingTheChangeMerged() {
        ChangeEntity change = changeEntity();
        when(changeRepository.findByTaskId("task-1")).thenReturn(Optional.of(change));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(boundProject()));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(githubRepoPort.hasNewCommits("gh-token", "octo/repo", "main", "preview"))
                .thenThrow(new IllegalStateException("GitHub API 오류 (status=502)"));

        assertThatThrownBy(() -> service.reflect(approval(9L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("502");

        assertThat(change.getStatus()).isEqualTo(ChangeStatus.PREVIEW_READY.name());
        verify(changeRepository, never()).save(any());
    }

    @Test
    void reflectRefreshesAnExpiredTokenBeforeCallingGithub() {
        ChangeEntity change = changeEntity();
        when(changeRepository.findByTaskId("task-1")).thenReturn(Optional.of(change));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(boundProject()));
        User expired = new User(1L, new GithubId("123"), "octo", null, 100L,
                "expired-token", "refresh-token", LocalDateTime.now().minusMinutes(1));
        User refreshed = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(expired), Optional.of(refreshed));
        when(githubRepoPort.hasNewCommits("gh-token", "octo/repo", "main", "preview")).thenReturn(false);
        when(githubRepoPort.getHeadCommitSha("gh-token", "octo/repo", "main")).thenReturn("head-sha");
        when(changeRepository.save(any(ChangeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.reflect(approval(9L));

        verify(authCommandService).refreshGithubUserToken(1L);
        verify(githubRepoPort).hasNewCommits("gh-token", "octo/repo", "main", "preview");
    }

    @Test
    void markRejectedSetsChangeStatusAndApprovalId() {
        ChangeEntity change = changeEntity();
        when(changeRepository.findByTaskId("task-1")).thenReturn(Optional.of(change));
        when(changeRepository.save(any(ChangeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.markRejected(approval(9L));

        assertThat(change.getStatus()).isEqualTo(ChangeStatus.REJECTED.name());
        assertThat(change.getApprovalId()).isEqualTo(9L);
        verify(changeRepository).save(change);
    }

    @Test
    void markRejectedIsANoOpWhenTheChangeIsMissing() {
        when(changeRepository.findByTaskId("task-1")).thenReturn(Optional.empty());

        service.markRejected(approval(9L)); // must not throw

        verify(changeRepository, never()).save(any());
    }

    private ChangeEntity changeEntity() {
        return new ChangeEntity(1L, 11L, 21L, "task-1", "preview-session-1", "요약", "diff");
    }

    private Approval approval(Long id) {
        return new Approval(id, 1L, 11L, 21L, "task-1", ApprovalType.RESULT, ApprovalStatus.PENDING,
                "[결과 반영] 요약", LocalDateTime.now(), null);
    }

    private Project boundProject() {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L, 1L, "my-project", ProjectStatus.ACTIVE, "vue", null, "fast", DeployStatus.LIVE,
                null, "v3", "octo/repo", "octo/repo", RepositoryVisibility.PUBLIC,
                RepositoryBindingStatus.BOUND, RepositoryHealthStatus.HEALTHY, false, now, now
        );
    }

    private User activeUser() {
        return new User(1L, new GithubId("123"), "octo", null, 100L, "gh-token", "refresh-token",
                LocalDateTime.now().plusHours(1));
    }
}
