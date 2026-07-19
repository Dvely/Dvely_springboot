package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskFailure;
import com.example.dvely.agent.application.exception.CodeAgentExecutionException;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BuildFailureRecoveryService {

    private final TaskStore taskStore;
    private final ApprovalRepository approvalRepository;
    private final ProjectApprovalPolicyRepository policyRepository;
    private final AgentMessageService agentMessageService;
    // Issue #64 follow-up (HIGH-1, retry-toctou-review.md): the automatic-recovery retry below
    // now goes through this instead of TaskStore directly, so it shares AgentOrchestrator#retry's
    // task-row lock with every other retry() caller (user's manual POST /retry, approve()'s
    // FAILED-branch) — see the field's use below for why a second, unguarded entry point into
    // TaskStore#retry defeated #64's fix.
    private final AgentOrchestrator agentOrchestrator;

    public void handle(String taskId, CodeAgentExecutionException exception) {
        taskStore.markFailed(
                taskId,
                exception.userMessage(),
                exception.logExcerpt(),
                exception.suggestedFix()
        );
        AgentTask task = taskStore.get(taskId);
        if (task == null) {
            return;
        }
        AgentTaskFailure failure = taskStore.getFailure(taskId, task.ownerUserId());
        if (failure == null || !failure.retryable()) {
            agentMessageService.appendAssistant(
                    task.conversationId(),
                    buildFailureMessage(exception, null, false)
            );
            return;
        }

        if (!requiresApproval(task.projectId())) {
            // Review follow-up (HIGH-1): this used to call taskStore.retry(taskId, ...) directly
            // — no taskStore.lockTask, so this automatic-recovery path and a concurrent manual
            // retry (AgentController#retryTask -> AgentOrchestrator#retry, already guarded by
            // #64) could both observe FAILED and both perform the transition: no exception, no
            // deadlock, just a second RETRY_QUEUED audit event for a single real retry (measured
            // 5/5 by review; attempt itself stayed correct only because TaskStore#retry has no
            // @Version and both racers happened to compute the same target row values — a
            // silent-corruption near-miss, not a guarantee). Calling the same guarded entry point
            // every other caller uses serializes this on the identical task-row mutex, so the
            // TOCTOU class #64 set out to remove is now actually closed for every caller, not
            // just the manual one.
            boolean retried = agentOrchestrator.retry(taskId, task.ownerUserId());
            if (!retried) {
                // Lost the task-row lock race to a concurrent actor (typically the user's own
                // manual retry) that already performed the exact same transition first — the
                // task is still moving forward correctly, just not via this call, so this is a
                // correct, silent no-op rather than an error: appending a second "자동 재시도를
                // 시작합니다" message here would misrepresent this call as having done something
                // it did not.
                log.info("[BuildFailureRecoveryService] 자동 재시도가 동시 요청에 선점되어 생략되었습니다. "
                        + "taskId={}", taskId);
                return;
            }
            agentMessageService.appendAssistant(
                    task.conversationId(),
                    buildFailureMessage(exception, null, true)
            );
            return;
        }

        Approval approval = findPendingRecoveryApproval(taskId)
                .orElseGet(() -> approvalRepository.save(new Approval(
                        task.ownerUserId(),
                        task.projectId(),
                        task.conversationId(),
                        task.taskId(),
                        ApprovalType.CHANGE,
                        "자동 수정 및 재build: " + exception.suggestedFix()
                )));
        agentMessageService.appendAssistant(
                task.conversationId(),
                buildFailureMessage(exception, approval.getId(), true)
        );
    }

    private boolean requiresApproval(Long projectId) {
        ProjectApprovalPolicy policy = projectId == null
                ? new ProjectApprovalPolicy(null)
                : policyRepository.findByProjectId(projectId)
                        .orElseGet(() -> new ProjectApprovalPolicy(projectId));
        return policy.requires(ApprovalType.CHANGE);
    }

    private java.util.Optional<Approval> findPendingRecoveryApproval(String taskId) {
        List<Approval> approvals = approvalRepository.findByTaskIdOrderByIdAsc(taskId);
        return approvals.stream()
                .filter(approval -> approval.getType() == ApprovalType.CHANGE)
                .filter(approval -> approval.getStatus() == ApprovalStatus.PENDING)
                .findFirst();
    }

    private String buildFailureMessage(CodeAgentExecutionException exception,
                                       Long approvalId,
                                       boolean retryable) {
        StringBuilder message = new StringBuilder()
                .append(exception.userMessage())
                .append("\n\n수정안: ")
                .append(exception.suggestedFix())
                .append("\n\n로그 일부:\n")
                .append(exception.logExcerpt());
        if (approvalId != null) {
            message.append("\n\n승인 [").append(approvalId).append("] 후 자동으로 수정 및 재build합니다.");
        } else if (retryable) {
            message.append("\n\n프로젝트 정책에 따라 자동 재시도를 시작합니다.");
        } else {
            message.append("\n\n최대 재시도 횟수에 도달했습니다.");
        }
        return message.toString();
    }
}
