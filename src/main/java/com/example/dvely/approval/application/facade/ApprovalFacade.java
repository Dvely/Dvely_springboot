package com.example.dvely.approval.application.facade;

import com.example.dvely.approval.application.command.ApprovalCommandService;
import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.common.paging.CursorPage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApprovalFacade {

    private final ApprovalQueryService queryService;
    private final ApprovalCommandService commandService;

    public CursorPage<ApprovalResult> getProjectApprovals(Long ownerUserId,
                                                         Long projectId,
                                                         Integer limit,
                                                         String after) {
        return queryService.getProjectApprovals(ownerUserId, projectId, limit, after);
    }

    public ApprovalResult getApproval(Long ownerUserId, Long approvalId) {
        return queryService.getApproval(ownerUserId, approvalId);
    }

    public ApprovalResult approve(Long ownerUserId, Long approvalId) {
        return commandService.approve(ownerUserId, approvalId);
    }

    public ApprovalResult approve(Long ownerUserId, Long approvalId, String repositoryName) {
        return commandService.approve(ownerUserId, approvalId, repositoryName);
    }

    public ApprovalResult reject(Long ownerUserId, Long approvalId) {
        return commandService.reject(ownerUserId, approvalId);
    }
}
