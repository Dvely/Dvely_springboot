package com.example.dvely.approval.presentation;

import com.example.dvely.approval.application.facade.ApprovalFacade;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.presentation.dto.ApprovalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Approval", description = "Agent 작업 승인 및 거절 API")
@RestController
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalFacade approvalFacade;

    @Operation(summary = "프로젝트 승인 목록 조회")
    @GetMapping("/api/v1/projects/{projectId}/approvals")
    public List<ApprovalResponse> getProjectApprovals(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        return approvalFacade.getProjectApprovals(ownerUserId, projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Operation(summary = "승인 상세 조회")
    @GetMapping("/api/v1/approvals/{approvalId}")
    public ApprovalResponse getApproval(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long approvalId
    ) {
        return toResponse(approvalFacade.getApproval(ownerUserId, approvalId));
    }

    @Operation(summary = "Agent 작업 승인")
    @PostMapping("/api/v1/approvals/{approvalId}/approve")
    public ApprovalResponse approve(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long approvalId
    ) {
        return toResponse(approvalFacade.approve(ownerUserId, approvalId));
    }

    @Operation(summary = "Agent 작업 거절")
    @PostMapping("/api/v1/approvals/{approvalId}/reject")
    public ApprovalResponse reject(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long approvalId
    ) {
        return toResponse(approvalFacade.reject(ownerUserId, approvalId));
    }

    private ApprovalResponse toResponse(ApprovalResult result) {
        return new ApprovalResponse(
                result.approvalId(),
                result.projectId(),
                result.conversationId(),
                result.taskId(),
                result.type(),
                result.status(),
                result.summary(),
                result.createdAt(),
                result.decidedAt()
        );
    }
}
