package com.example.dvely.change.application.service;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.change.domain.value.ChangeStatus;
import com.example.dvely.change.infrastructure.persistence.entity.ChangeEntity;
import com.example.dvely.change.infrastructure.persistence.repository.SpringDataChangeRepository;
import com.example.dvely.deployment.application.port.out.GithubRepoPort;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Track Z (#56) — the "git 반영" half of the result-approval gate: turns an APPROVED/REJECTED
 * RESULT decision into an actual change to the {@code project_changes} row and (on approve) a
 * real GitHub merge. Called from {@code ApprovalCommandService.approve/reject}'s RESULT branch,
 * inside their existing {@code @Transactional} boundary (design D8) — so a GitHub failure here
 * rolls back the approval's own status change too, leaving it PENDING for the user to retry or
 * reject instead of stranding it "decided but not reflected".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultApprovalService {

    private static final String PREVIEW_BRANCH = "preview";
    private static final String MAIN_BRANCH = "main";
    private static final String PR_TITLE = "[Qeploy] Apply approved result to main";

    private final SpringDataChangeRepository changeRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;
    private final GithubRepoPort githubRepoPort;
    private final ApprovalRepository approvalRepository;

    /**
     * @throws IllegalStateException (-> 409, E-RA-05) if no Change row exists for the approval's
     *         task — the gate always records the Change before it ever creates a RESULT approval
     *         (§5.2 ordering), so a missing row here means the gate's own contract was violated,
     *         not a normal user-facing condition.
     */
    @Transactional
    public ReflectResult reflect(Approval approval) {
        ChangeEntity change = changeRepository.findByTaskId(approval.getTaskId())
                .orElseThrow(() -> new IllegalStateException(
                        "RESULT 승인에 연결된 Change가 없습니다(게이트 계약 위반). taskId=" + approval.getTaskId()));
        Project project = projectRepository
                .findByIdAndOwnerUserIdAndDeletedFalse(approval.getProjectId(), approval.getOwnerUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "RESULT 승인의 프로젝트를 찾을 수 없습니다. projectId=" + approval.getProjectId()));
        String repoFullName = project.getSourceRepository();
        String userToken = resolveUserToken(approval.getOwnerUserId());

        long startedAt = System.currentTimeMillis();
        Integer prNumber;
        String mergeCommitSha;
        if (githubRepoPort.hasNewCommits(userToken, repoFullName, MAIN_BRANCH, PREVIEW_BRANCH)) {
            prNumber = githubRepoPort.createOrGetPullRequest(
                    userToken, repoFullName, PREVIEW_BRANCH, MAIN_BRANCH, PR_TITLE);
            mergeCommitSha = githubRepoPort.mergePullRequest(userToken, repoFullName, prNumber);
            log.info("[ResultApproval] preview→main 반영 완료 | taskId={} pr=#{} sha={} elapsedMs={}",
                    approval.getTaskId(), prNumber, mergeCommitSha, System.currentTimeMillis() - startedAt);
        } else {
            // D8 idempotency: either a prior approve attempt already merged this (and failed only
            // on the DB write that follows), or the CODE step produced no net diff. Either way
            // there is nothing new to merge — record main's current head as the "result" so a
            // retried approve converges to MERGED without erroring or double-merging.
            prNumber = null;
            mergeCommitSha = githubRepoPort.getHeadCommitSha(userToken, repoFullName, MAIN_BRANCH);
            log.info("[ResultApproval] merge 생략(신규 커밋 없음, 멱등 경로) | taskId={} sha={}",
                    approval.getTaskId(), mergeCommitSha);
        }
        change.markMerged(approval.getId(), prNumber, mergeCommitSha);
        changeRepository.save(change);
        return new ReflectResult(prNumber, mergeCommitSha);
    }

    /**
     * No-op (rather than throw) when the Change row is missing — unlike {@link #reflect}, a
     * rejection has no external side effect to reconcile, so there is nothing gained by failing
     * the request over a "should never happen" condition; {@code AgentOrchestrator.reject} still
     * cancels the task either way.
     */
    @Transactional
    public void markRejected(Approval approval) {
        changeRepository.findByTaskId(approval.getTaskId()).ifPresent(change -> {
            change.markRejected(approval.getId());
            changeRepository.save(change);
        });
    }

    /**
     * Track Z (#56) review follow-up (BLOCKING-1, extended by issue #62 B1): the single, shared
     * answer to "is this project already under the RESULT gate's jurisdiction" — i.e. has the
     * gate already decided at least one Change for it, *or* is it currently waiting on a decision.
     * A REJECTED Change means a user explicitly declined to bring some preview content onto main;
     * a MERGED Change means the gate already merged at least once; a PENDING RESULT approval means
     * the gate has fired and a decision is in flight but not made yet. Any of these three facts
     * means this project must never be treated as "never gated yet".
     * <p>
     * Consumed by {@code DeploymentCommandService#prepareRelease}'s {@code mergeAllowed} rule
     * (§5.4): that rule's "this project's very first release" carve-out (needed so a brand-new
     * project's initial content can still reach {@code main} even though D9 never had a chance to
     * gate it) must not also cover a project that already went through — or is currently going
     * through — the RESULT gate. Without the REJECTED/MERGED check, a direct deploy could merge
     * exactly the content a user had explicitly rejected, simply because the project happened to
     * still show {@code currentVersion == null} (never successfully published). Without the
     * PENDING check added here, a direct deploy racing an in-flight RESULT approval on that same
     * never-yet-released project could merge preview content the user has not decided on at all
     * (issue #62 B1 — narrower than BLOCKING-1's "already-REJECTED" case, but the same class of
     * bug: a not-yet-decided or already-declined Change reaching {@code main} through the side
     * door).
     */
    @Transactional(readOnly = true)
    public boolean hasResultGateHistory(Long projectId) {
        return changeRepository.existsByProjectIdAndStatusIn(
                projectId, List.of(ChangeStatus.REJECTED.name(), ChangeStatus.MERGED.name()))
                || approvalRepository.existsByProjectIdAndTypeAndStatus(
                        projectId, ApprovalType.RESULT, ApprovalStatus.PENDING);
    }

    private String resolveUserToken(Long ownerUserId) {
        User user = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new IllegalStateException("유저를 찾을 수 없습니다. userId=" + ownerUserId));
        if (user.isUserAccessTokenExpired()) {
            authCommandService.refreshGithubUserToken(ownerUserId);
            user = userRepository.findById(ownerUserId).orElseThrow();
        }
        return user.getGithubUserAccessToken();
    }

    public record ReflectResult(Integer prNumber, String mergeCommitSha) {
    }
}
