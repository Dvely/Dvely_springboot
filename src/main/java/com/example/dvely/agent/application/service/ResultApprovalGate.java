package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Track Z (#56) — the "결과 승인" (RESULT approval) gate. Evaluated once, right after the last CODE
 * step of a plan finishes ({@code AgentPlanExecutor}): if the project is BOUND to a GitHub repo
 * and its policy requires RESULT approval (design D9), this pushes the CODE step's output to the
 * {@code preview} branch, parks the task in {@code WAITING_RESULT_APPROVAL}, and opens a RESULT
 * approval for the user to review preview + diff before it is allowed onto {@code main}.
 * <p>
 * Deliberately excludes unbound/new-app projects (D9): there is no repository yet for a RESULT
 * approval to "reflect" into, so gating them would create a dead approval that can never do
 * anything — their first publish stays owned by the DEPLOY step exactly as before this feature.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultApprovalGate {

    private final ProjectRepository projectRepository;
    private final ProjectApprovalPolicyRepository policyRepository;
    private final PreviewSessionService previewSessionService;
    private final PreviewBranchPushService previewBranchPushService;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;
    private final TaskStore taskStore;
    private final ApprovalRepository approvalRepository;
    private final AgentMessageService agentMessageService;

    /**
     * @param stepIndex zero-based index, within {@code plan.steps()}, of the CODE step that just
     *                  finished. Only fires for the plan's <em>last</em> CODE step — a plan with
     *                  several CODE steps (e.g. retried after a mid-plan build failure) only ever
     *                  opens one RESULT approval, covering every CODE step's cumulative diff.
     * @return true iff the gate fired. Callers must stop executing the plan without touching
     *         {@code markDone}/{@code removePlan} when this returns true — the plan stays saved
     *         so {@code AgentOrchestrator.resumeAfterResult} can continue at {@code currentStep}
     *         once the user decides.
     *         <p>
     *         Deliberately not {@code @Transactional} as a whole (unlike {@code ResultApprovalService
     *         .reflect}, which runs inside {@code ApprovalCommandService.approve}'s existing
     *         transaction): this runs from {@code AgentPlanExecutor}, an {@code @Async} task with
     *         no ambient transaction to join (same "Case B" shape as {@code DeployAgentService}/
     *         {@code CodeAgentService} in this same call path), and it includes a slow docker-exec
     *         git push that must not hold a DB transaction open while it runs. Each write below
     *         ({@code markStepCompleted}, {@code markWaitingResultApproval}, the approval save) is
     *         already self-transactional; a failure between them surfaces via the exception
     *         propagating out to {@code AgentPlanExecutor}'s catch-all (task -> FAILED, retryable)
     *         exactly as design §5.2 accounts for.
     */
    public boolean requestIfRequired(AgentPlan plan, int stepIndex, String taskId, Long userId, Long projectId) {
        if (!isLastCodeStep(plan, stepIndex) || projectId == null) {
            return false;
        }
        Project project = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .orElse(null);
        if (project == null || project.getRepositoryBindingStatus() != RepositoryBindingStatus.BOUND) {
            return false;
        }
        ProjectApprovalPolicy policy = policyRepository.findByProjectId(projectId)
                .orElseGet(() -> new ProjectApprovalPolicy(projectId));
        if (!policy.isResultApprovalRequired()) {
            return false;
        }

        pushPreviewBranch(taskId, userId, project);

        // §5.2 ordering: completeStep only after the push above has actually succeeded, so a push
        // failure (network/token — caught by AgentPlanExecutor's outer catch-all, which marks the
        // task FAILED without reaching this point) leaves currentStep pointing at this CODE step
        // rather than past it — /retry then re-runs the CODE step instead of silently skipping to
        // whatever comes next (or, for a CODE-only plan, straight to a false DONE with nothing
        // ever pushed). completeStep before the WAITING_RESULT_APPROVAL transition below is what
        // makes resumeAfterResult's later replay start at the right place.
        taskStore.markStepCompleted(taskId, stepIndex + 1);

        AgentTask task = taskStore.get(taskId);
        String resultSummary = task == null || task.summary() == null || task.summary().isBlank()
                ? "CODE 작업 결과"
                : task.summary();
        // State transition strictly before the approval row is created (design D3/§5.2): once the
        // approval becomes visible to the user, the task must already be in a state where
        // resumeAfterResult's guard accepts it — otherwise an approve arriving in the ms window
        // between these two writes would 409 against a task that still looks RUNNING.
        taskStore.markWaitingResultApproval(taskId, "[결과 반영] " + resultSummary);
        Approval approval = approvalRepository.save(new Approval(
                userId,
                projectId,
                task == null ? null : task.conversationId(),
                taskId,
                ApprovalType.RESULT,
                "[결과 반영] " + resultSummary
        ));
        log.info("[ResultApprovalGate] 결과 승인 게이트 발동 | taskId={} approvalId={} projectId={}",
                taskId, approval.getId(), projectId);
        agentMessageService.appendAssistant(
                task == null ? null : task.conversationId(),
                buildGateMessage(task == null ? null : task.previewUrl(), approval)
        );
        return true;
    }

    private boolean isLastCodeStep(AgentPlan plan, int stepIndex) {
        if (plan.steps().get(stepIndex).agentType() != AgentType.CODE) {
            return false;
        }
        for (int i = stepIndex + 1; i < plan.steps().size(); i++) {
            AgentStep later = plan.steps().get(i);
            if (later.agentType() == AgentType.CODE) {
                return false;
            }
        }
        return true;
    }

    private void pushPreviewBranch(String taskId, Long userId, Project project) {
        PreviewSessionInfo previewSession = previewSessionService.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "결과 승인 게이트에 필요한 PreviewSession이 없습니다. taskId=" + taskId));
        User user = resolveUser(userId);
        previewBranchPushService.push(
                previewSession.containerId(),
                user.getGithubUserAccessToken(),
                user.getUsername(),
                project.getSourceRepository(),
                false,
                taskId
        );
    }

    private User resolveUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("유저를 찾을 수 없습니다. userId=" + userId));
        if (user.isUserAccessTokenExpired()) {
            authCommandService.refreshGithubUserToken(userId);
            user = userRepository.findById(userId).orElseThrow();
        }
        return user;
    }

    private String buildGateMessage(String previewUrl, Approval approval) {
        return "작업 결과가 preview에 준비되었습니다. 미리보기와 변경 내역을 확인해 주세요.\n"
                + "승인하면 현재 preview 상태 전체가 main에 반영됩니다. 거절하면 preview에만 남습니다.\n"
                + "- preview: " + previewUrl + "\n"
                + "- [" + approval.getId() + "] RESULT: " + approval.getSummary();
    }
}
