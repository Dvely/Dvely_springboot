package com.example.dvely.approval.application.facade;

import com.example.dvely.approval.application.command.ApprovalCommandService;
import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.domain.value.RejectOption;
import com.example.dvely.change.application.result.ResultScope;
import com.example.dvely.change.application.service.ResultScopeService;
import com.example.dvely.common.paging.CursorPage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApprovalFacade {

    private final ApprovalQueryService queryService;
    private final ApprovalCommandService commandService;
    private final ResultScopeService resultScopeService;

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

    public ApprovalResult reject(Long ownerUserId, Long approvalId, RejectOption option) {
        return commandService.reject(ownerUserId, approvalId, option);
    }

    public ResultScope getResultScope(Long ownerUserId, Long approvalId) {
        return resultScopeService.view(ownerUserId, approvalId);
    }
}
