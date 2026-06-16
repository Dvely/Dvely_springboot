package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskFailure;
import com.example.dvely.agent.application.exception.CodeAgentExecutionException;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BuildFailureRecoveryService {

    private final TaskStore taskStore;
    private final ApprovalRepository approvalRepository;
    private final ProjectApprovalPolicyRepository policyRepository;
    private final AgentMessageService agentMessageService;

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
            taskStore.retry(taskId, task.ownerUserId());
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
