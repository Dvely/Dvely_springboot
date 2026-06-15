package com.example.dvely.approval.application.facade;

import com.example.dvely.approval.application.command.ApprovalCommandService;
import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.approval.application.result.ApprovalResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApprovalFacade {

    private final ApprovalQueryService queryService;
    private final ApprovalCommandService commandService;

    public List<ApprovalResult> getProjectApprovals(Long ownerUserId, Long projectId) {
        return queryService.getProjectApprovals(ownerUserId, projectId);
    }

    public ApprovalResult getApproval(Long ownerUserId, Long approvalId) {
        return queryService.getApproval(ownerUserId, approvalId);
    }

    public ApprovalResult approve(Long ownerUserId, Long approvalId) {
        return commandService.approve(ownerUserId, approvalId);
    }

    public ApprovalResult reject(Long ownerUserId, Long approvalId) {
        return commandService.reject(ownerUserId, approvalId);
    }
}
