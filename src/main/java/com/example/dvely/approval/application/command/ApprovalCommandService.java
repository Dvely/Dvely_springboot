package com.example.dvely.approval.application.command;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.application.port.out.StandaloneApprovalHandler;
import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.repository.ApprovalRouting;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.change.application.service.ResultApprovalService;
import com.example.dvely.common.exception.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApprovalCommandService {

    private final ApprovalRepository approvalRepository;
    private final ApprovalQueryService queryService;
    private final AgentOrchestrator agentOrchestrator;
    private final AgentMessageService agentMessageService;
    // Spring injects every StandaloneApprovalHandler bean here (an empty list is fine — it just
    // means no standalone ApprovalType is wired yet). Kept as a List rather than a Map so new
    // handlers register themselves purely by implementing the interface (design D6/§4.1) with
    // no change needed in this service.
    private final List<StandaloneApprovalHandler> standaloneHandlers;
    // Track Z (#56): RESULT is never standalone (always task-bound, created only by
    // ResultApprovalGate) and never joins the allApproved plan-approval vote below — it gets its
    // own branch between the two, matching design D2/§4.2.
    private final ResultApprovalService resultApprovalService;
    // ADR-Y1 (#55): the task-row mutex (agent_runs, the aggregate root every task-bound decision
    // must lock FIRST — see TaskStore#lockTask) lives in the agent module's store, not here. This
    // service already reaches into the agent module for AgentOrchestrator/AgentMessageService, so
    // this is not a new layering precedent — approval and agent are tightly coupled by design
    // (approval gates agent task execution).
    private final TaskStore taskStore;

    @Transactional
    public ApprovalResult approve(Long ownerUserId, Long approvalId) {
        ApprovalRouting routing = routingFor(ownerUserId, approvalId);
        if (routing.isStandalone()) {
            // Standalone: single row, no task to lock, order irrelevant (design §1 step②).
            Approval approval = findOwnedForDecide(ownerUserId, approvalId);
            approval.approve();
            Approval saved = approvalRepository.save(approval);
            resolveStandaloneHandler(saved.getType()).onApproved(saved);
            return queryService.toResult(saved);
        }

        // ADR-Y1 §1 step③: task row lock FIRST — the lock-hierarchy root every task-bound decision
        // path (approve/reject/cancel/RESULT/sweep) funnels through, structurally closing the D1
        // write-skew race (two approvals on the same task decided concurrently, each reading the
        // other's not-yet-committed PENDING row and both skipping executeApproved). This also
        // supersedes #56's RESULT-branch ordering (approval row -> task row, #62 B3's flagged
        // inversion) — every branch below, RESULT included, now locks task-then-approval.
        taskStore.lockTask(routing.taskId());
        // Fresh fetch under lock: step① deliberately used a scalar projection instead of loading
        // the Approval entity, so there is no stale L1-cached instance for this FOR UPDATE fetch
        // to collide with (see ApprovalRouting's javadoc).
        Approval approval = findOwnedForDecide(ownerUserId, approvalId);
        approval.approve();
        Approval saved = approvalRepository.save(approval);

        if (saved.getType() == ApprovalType.RESULT) {
            // Review follow-up (BLOCKING-3, carried forward): verify + lock the rollback-able DB
            // precondition (task must still be WAITING_RESULT_APPROVAL) BEFORE reflect()'s
            // irreversible external GitHub merge below. The task row is already locked (step③
            // above), so this call only re-affirms a lock this transaction already holds — no new
            // contention, no possibility of the ordering inversion #62 B3 flagged.
            agentOrchestrator.verifyResumableAfterResult(saved.getTaskId());
            ResultApprovalService.ReflectResult reflectResult = resultApprovalService.reflect(saved);
            agentOrchestrator.resumeAfterResult(saved.getTaskId());
            agentMessageService.appendAssistant(
                    saved.getConversationId(),
                    buildResultApprovedMessage(reflectResult)
            );
            return queryService.toResult(saved);
        }

        // All-approved check MUST be a locking read (design §1 "왜 all-approved 검사가 locking
        // read여야 하는가"): under REPEATABLE READ, a plain SELECT can still return a pre-lock
        // snapshot even after this transaction waited for and acquired the task lock above,
        // because that snapshot was fixed at this transaction's first non-locking read, not at
        // lock-acquisition time. A locking read always observes the latest committed version. This
        // never actually contends in practice — the task lock already serialized every other
        // decision-maker for this taskId out.
        List<Approval> taskApprovals = approvalRepository.findByTaskIdOrderByIdAscForUpdate(saved.getTaskId());
        boolean allApproved = !taskApprovals.isEmpty() && taskApprovals.stream()
                .allMatch(item -> item.getStatus() == ApprovalStatus.APPROVED);
        if (allApproved) {
            agentMessageService.appendAssistant(saved.getConversationId(), "모든 승인이 완료되어 작업을 시작합니다.");
            agentOrchestrator.executeApproved(saved.getTaskId());
        }
        return queryService.toResult(saved);
    }

    @Transactional
    public ApprovalResult reject(Long ownerUserId, Long approvalId) {
        ApprovalRouting routing = routingFor(ownerUserId, approvalId);
        if (routing.isStandalone()) {
            Approval approval = findOwnedForDecide(ownerUserId, approvalId);
            approval.reject();
            Approval saved = approvalRepository.save(approval);
            resolveStandaloneHandler(saved.getType()).onRejected(saved);
            return queryService.toResult(saved);
        }

        // Same ADR-Y1 lock order as approve() above — reject is just as much a task-bound decision
        // and must serialize against a concurrent approve/reject/cancel/sweep on the same task.
        taskStore.lockTask(routing.taskId());
        Approval approval = findOwnedForDecide(ownerUserId, approvalId);
        approval.reject();
        Approval saved = approvalRepository.save(approval);

        if (saved.getType() == ApprovalType.RESULT) {
            resultApprovalService.markRejected(saved);
            // AgentOrchestrator.reject cascades to sibling PENDING approvals (Y6-a) — see its
            // javadoc for why reject and cancel now share that behavior.
            agentOrchestrator.reject(saved.getTaskId(), ownerUserId);
            agentMessageService.appendAssistant(
                    saved.getConversationId(),
                    "결과가 거절되어 main에 반영하지 않았습니다. 변경은 preview 브랜치에만 남아 있습니다.\n"
                            + "이어서 수정을 요청하면 현재 preview 상태 위에서 작업합니다."
            );
            return queryService.toResult(saved);
        }

        agentOrchestrator.reject(saved.getTaskId(), ownerUserId);
        agentMessageService.appendAssistant(
                saved.getConversationId(),
                "작업이 거절되어 실행하지 않았습니다: " + saved.getSummary()
        );
        return queryService.toResult(saved);
    }

    // ADR-Y1 §1 step①: unlocked scalar routing lookup so approve/reject can decide standalone-vs-
    // task-bound (and therefore which lock order to take) BEFORE acquiring any lock. Throws 404
    // here, with no lock ever taken, if the approval does not exist / is not owned by this user.
    private ApprovalRouting routingFor(Long ownerUserId, Long approvalId) {
        return approvalRepository.findRoutingInfo(approvalId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("승인을 찾을 수 없습니다. approvalId=" + approvalId));
    }

    private String buildResultApprovedMessage(ResultApprovalService.ReflectResult reflectResult) {
        StringBuilder message = new StringBuilder("결과가 승인되어 main에 반영되었습니다.");
        if (reflectResult.prNumber() != null) {
            message.append("\n- PR: #").append(reflectResult.prNumber());
        }
        if (reflectResult.mergeCommitSha() != null) {
            message.append("\n- commit: ")
                    .append(reflectResult.mergeCommitSha(), 0, Math.min(7, reflectResult.mergeCommitSha().length()));
        }
        message.append("\n남은 작업을 이어서 진행합니다.");
        return message.toString();
    }

    // Both approve() and reject() funnel through this single locked lookup (review F1) — the
    // approval row is the one point of contention for a given approvalId, so a concurrent
    // approve+reject pair serializes here: the second call's SELECT ... FOR UPDATE blocks until
    // the first transaction commits, then observes the now-APPROVED/REJECTED row and fails
    // Approval#decide's PENDING guard (409) instead of silently overwriting the first outcome.
    // Plain reads (ApprovalQueryService) intentionally keep using the unlocked lookup — only
    // decision paths need the lock.
    private Approval findOwnedForDecide(Long ownerUserId, Long approvalId) {
        return approvalRepository.findByIdAndOwnerUserIdForUpdate(approvalId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("승인을 찾을 수 없습니다. approvalId=" + approvalId));
    }

    // A standalone ApprovalType with no registered handler is a server configuration defect
    // (an ApprovalType was wired into standalone creation without a matching handler bean) —
    // not a client error, but it must still fail the request rather than silently no-op, so it
    // surfaces as a 409 that GlobalExceptionHandler logs at warn level for operators to notice.
    private StandaloneApprovalHandler resolveStandaloneHandler(ApprovalType type) {
        return standaloneHandlers.stream()
                .filter(handler -> handler.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("standalone 승인을 처리할 핸들러가 없습니다. type=" + type));
    }
}
