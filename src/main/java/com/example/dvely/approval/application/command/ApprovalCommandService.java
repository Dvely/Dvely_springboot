package com.example.dvely.approval.application.command;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.approval.application.port.out.StandaloneApprovalHandler;
import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
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

    @Transactional
    public ApprovalResult approve(Long ownerUserId, Long approvalId) {
        Approval approval = findOwnedForDecide(ownerUserId, approvalId);
        approval.approve();
        Approval saved = approvalRepository.save(approval);

        if (saved.isStandalone()) {
            resolveStandaloneHandler(saved.getType()).onApproved(saved);
            return queryService.toResult(saved);
        }

        if (saved.getType() == ApprovalType.RESULT) {
            ResultApprovalService.ReflectResult reflectResult = resultApprovalService.reflect(saved);
            agentOrchestrator.resumeAfterResult(saved.getTaskId());
            agentMessageService.appendAssistant(
                    saved.getConversationId(),
                    buildResultApprovedMessage(reflectResult)
            );
            return queryService.toResult(saved);
        }

        List<Approval> taskApprovals = approvalRepository.findByTaskIdOrderByIdAsc(saved.getTaskId());
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
        Approval approval = findOwnedForDecide(ownerUserId, approvalId);
        approval.reject();
        Approval saved = approvalRepository.save(approval);

        if (saved.isStandalone()) {
            resolveStandaloneHandler(saved.getType()).onRejected(saved);
            return queryService.toResult(saved);
        }

        if (saved.getType() == ApprovalType.RESULT) {
            resultApprovalService.markRejected(saved);
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
