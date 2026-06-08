package com.example.dvely.approval.application.command;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
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

    @Transactional
    public ApprovalResult approve(Long ownerUserId, Long approvalId) {
        Approval approval = findOwned(ownerUserId, approvalId);
        approval.approve();
        Approval saved = approvalRepository.save(approval);

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
        Approval approval = findOwned(ownerUserId, approvalId);
        approval.reject();
        Approval saved = approvalRepository.save(approval);
        agentOrchestrator.reject(saved.getTaskId(), ownerUserId);
        agentMessageService.appendAssistant(
                saved.getConversationId(),
                "작업이 거절되어 실행하지 않았습니다: " + saved.getSummary()
        );
        return queryService.toResult(saved);
    }

    private Approval findOwned(Long ownerUserId, Long approvalId) {
        return approvalRepository.findByIdAndOwnerUserId(approvalId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("승인을 찾을 수 없습니다. approvalId=" + approvalId));
    }
}
