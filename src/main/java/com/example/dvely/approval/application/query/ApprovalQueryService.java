package com.example.dvely.approval.application.query;

import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApprovalQueryService {

    private final ApprovalRepository approvalRepository;
    private final ProjectRepository projectRepository;

    public List<ApprovalResult> getProjectApprovals(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
        return approvalRepository
                .findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(projectId, ownerUserId)
                .stream()
                .map(this::toResult)
                .toList();
    }

    public ApprovalResult getApproval(Long ownerUserId, Long approvalId) {
        return toResult(findOwned(ownerUserId, approvalId));
    }

    private Approval findOwned(Long ownerUserId, Long approvalId) {
        return approvalRepository.findByIdAndOwnerUserId(approvalId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("승인을 찾을 수 없습니다. approvalId=" + approvalId));
    }

    public ApprovalResult toResult(Approval approval) {
        return new ApprovalResult(
                approval.getId(),
                approval.getProjectId(),
                approval.getConversationId(),
                approval.getTaskId(),
                approval.getType().name(),
                approval.getStatus().name(),
                approval.getSummary(),
                approval.getCreatedAt(),
                approval.getDecidedAt()
        );
    }
}
