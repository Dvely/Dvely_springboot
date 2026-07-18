package com.example.dvely.agent.application.orchestrator;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.InfraOperation;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final TaskStore              taskStore;
    private final ProjectRepository      projectRepository;
    private final ConversationRepository conversationRepository;
    private final ProjectApprovalPolicyRepository policyRepository;
    private final ApprovalRepository approvalRepository;
    private final AgentMessageService agentMessageService;

    @Transactional
    public AgentSubmission submit(AgentPlan plan, Long userId, Long conversationId) {
        Long projectId = resolveProjectId(userId, plan.projectId(), conversationId);
        AgentPlan normalizedPlan = new AgentPlan(plan.steps(), plan.reasoning(), plan.aiProvider(), projectId);
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        taskStore.save(new AgentTask(
                taskId,
                userId,
                normalizedPlan.projectId(),
                conversationId,
                TaskStatus.PENDING,
                null,
                null,
                null,
                null,
                Instant.now()
        ));
        taskStore.savePlan(taskId, normalizedPlan);

        List<Approval> approvals = createRequiredApprovals(normalizedPlan, taskId, userId, conversationId);
        if (approvals.isEmpty()) {
            agentMessageService.appendAssistant(conversationId, "승인 정책에 따라 작업을 시작합니다.");
            taskStore.enqueue(taskId);
            return new AgentSubmission(taskId, TaskStatus.QUEUED, List.of());
        }

        String approvalSummary = buildApprovalMessage(approvals);
        taskStore.markWaitingApproval(taskId, approvalSummary);
        agentMessageService.appendAssistant(conversationId, approvalSummary);
        return new AgentSubmission(
                taskId,
                TaskStatus.WAITING_APPROVAL,
                approvals.stream().map(Approval::getId).toList()
        );
    }

    public void executeApproved(String taskId) {
        AgentTask task = taskStore.get(taskId);
        AgentPlan plan = taskStore.getPlan(taskId);
        if (task == null || plan == null) {
            throw new IllegalStateException("실행할 Agent task를 찾을 수 없습니다. taskId=" + taskId);
        }
        if (task.status() == TaskStatus.FAILED) {
            if (!taskStore.retry(taskId, task.ownerUserId())) {
                throw new IllegalStateException("재시도할 수 없는 Agent task입니다. taskId=" + taskId);
            }
            return;
        }
        taskStore.enqueue(taskId);
    }

    /**
     * Resumes a task past its RESULT approval (design D3/§4.2) — deliberately a separate method
     * from {@link #executeApproved}, not a reuse of it: {@code executeApproved} has a
     * FAILED-then-retry branch (build-failure recovery) that has nothing to do with this "resume
     * a still-in-progress task's remaining steps" case, and its "every approval decided" chat
     * message would be wrong here (RESULT approvals never join the plan-approval
     * {@code allApproved} vote — see {@code ApprovalCommandService}'s RESULT branch). Throws
     * (-> 409, E-RA-03) instead of silently no-op'ing so a racing duplicate approve — or an
     * approve arriving after the task was independently cancelled — surfaces as a conflict rather
     * than pretending to have resumed something that no longer needs resuming.
     */
    public void resumeAfterResult(String taskId) {
        if (!taskStore.resumeAfterResultApproval(taskId)) {
            throw new IllegalStateException(
                    "결과 승인 대기 상태가 아닌 Agent task입니다. taskId=" + taskId);
        }
    }

    public void reject(String taskId, Long ownerUserId) {
        if (!taskStore.cancel(taskId, ownerUserId)) {
            throw new IllegalStateException("거절할 Agent task를 찾을 수 없습니다. taskId=" + taskId);
        }
    }

    @Transactional
    public boolean cancel(String taskId, Long ownerUserId) {
        if (!taskStore.cancel(taskId, ownerUserId)) {
            return false;
        }
        approvalRepository.findByTaskIdOrderByIdAsc(taskId).stream()
                .filter(approval -> approval.getStatus() == ApprovalStatus.PENDING)
                .forEach(approval -> {
                    approval.cancel();
                    approvalRepository.save(approval);
                });
        return true;
    }

    public boolean retry(String taskId, Long ownerUserId) {
        boolean pendingApproval = approvalRepository.findByTaskIdOrderByIdAsc(taskId).stream()
                .anyMatch(approval -> approval.getStatus() == ApprovalStatus.PENDING);
        return !pendingApproval && taskStore.retry(taskId, ownerUserId);
    }

    private List<Approval> createRequiredApprovals(AgentPlan plan,
                                                   String taskId,
                                                   Long userId,
                                                   Long conversationId) {
        ProjectApprovalPolicy policy = resolvePolicy(plan.projectId());
        Map<ApprovalType, String> summaries = new LinkedHashMap<>();
        for (AgentStep step : plan.steps()) {
            ApprovalType type = toApprovalType(step);
            if (type == null || !policy.requires(type)) {
                continue;
            }
            summaries.putIfAbsent(type, summarize(step));
        }

        List<Approval> approvals = new ArrayList<>();
        summaries.forEach((type, summary) -> approvals.add(approvalRepository.save(new Approval(
                userId,
                plan.projectId(),
                conversationId,
                taskId,
                type,
                summary
        ))));
        return approvals;
    }

    private ProjectApprovalPolicy resolvePolicy(Long projectId) {
        if (projectId == null) {
            return new ProjectApprovalPolicy(null);
        }
        return policyRepository.findByProjectId(projectId)
                .orElseGet(() -> new ProjectApprovalPolicy(projectId));
    }

    /**
     * Takes the whole {@link AgentStep} (design D7), not just its {@link AgentType} — INFRA_OPERATE
     * is the first type whose approval requirement depends on a step parameter (the "operation"
     * catalog entry) rather than being fixed per type. An unparseable/missing operation and a
     * read-only or unsupported operation both resolve to {@code null} (no approval): the former
     * because there is nothing concrete to approve yet (InfraOpsAgentService will respond with
     * guidance instead), the latter per the catalog's own {@code approvalRequired()} contract.
     */
    private ApprovalType toApprovalType(AgentStep step) {
        return switch (step.agentType()) {
            case CODE -> ApprovalType.CHANGE;
            case DEPLOY -> ApprovalType.DEPLOYMENT;
            case DOMAIN_BIND -> ApprovalType.DOMAIN_BINDING;
            case INFRA_OPERATE -> InfraOperation.parse(step.parameters().get("operation"))
                    .filter(InfraOperation::approvalRequired)
                    .map(op -> ApprovalType.INFRA_OPERATION)
                    .orElse(null);
            case CHAT -> null;
        };
    }

    /**
     * Truncated instruction text used as the Approval row's one-line summary. For INFRA_OPERATE
     * steps that carry a detected impact (BI-176/177), the catalog's marker(s) — e.g.
     * {@code "[서비스 영향] "} — are prepended so the impact is visible at the single point the user
     * sees before approving (design D7/§16.7), rather than only being known internally to the
     * approval-gating decision above. Truncation stays at 200 chars on the instruction itself (the
     * marker is added on top of that, well within the summary column's 500-char limit).
     */
    private String summarize(AgentStep step) {
        String instruction = step.parameters().getOrDefault("instruction", "").trim();
        if (instruction.isEmpty()) {
            instruction = step.agentType().name() + " 작업";
        }
        String truncated = instruction.length() <= 200 ? instruction : instruction.substring(0, 197) + "...";
        String markers = step.agentType() == AgentType.INFRA_OPERATE
                ? InfraOperation.parse(step.parameters().get("operation")).map(InfraOperation::impactMarkers).orElse("")
                : "";
        return markers + truncated;
    }

    private String buildApprovalMessage(List<Approval> approvals) {
        StringBuilder message = new StringBuilder("작업 계획을 만들었습니다. 승인 후 실행합니다.");
        for (Approval approval : approvals) {
            message.append("\n- [")
                    .append(approval.getId())
                    .append("] ")
                    .append(approval.getType())
                    .append(": ")
                    .append(approval.getSummary());
        }
        return message.toString();
    }

    public Long resolveProjectId(Long userId, Long requestedProjectId, Long conversationId) {
        Long projectId = requestedProjectId;
        if (conversationId != null) {
            Conversation conversation = conversationRepository
                    .findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                    .orElseThrow(() -> new NotFoundException("대화를 찾을 수 없습니다. conversationId=" + conversationId));
            if (projectId != null && !projectId.equals(conversation.getProjectId())) {
                throw new IllegalArgumentException("대화와 프로젝트가 일치하지 않습니다.");
            }
            projectId = conversation.getProjectId();
        }

        if (projectId != null && projectRepository
                .findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .isEmpty()) {
            throw new NotFoundException("프로젝트를 찾을 수 없습니다. projectId=" + projectId);
        }

        return projectId;
    }
}
