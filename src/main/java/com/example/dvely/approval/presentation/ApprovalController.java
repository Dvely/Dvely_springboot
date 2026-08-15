package com.example.dvely.approval.presentation;

import com.example.dvely.approval.application.facade.ApprovalFacade;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.presentation.dto.ApprovalDecisionRequest;
import com.example.dvely.approval.presentation.dto.ApprovalInputResponse;
import com.example.dvely.approval.presentation.dto.ApprovalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Approval", description = "Agent 작업 승인 및 거절 API")
@RestController
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalFacade approvalFacade;

    @Operation(
            summary = "프로젝트 승인 목록 조회",
            description = "프로젝트에서 생성된 모든 승인 요청을 상태(PENDING/APPROVED/REJECTED/CANCELLED)와 " +
                          "무관하게 최신순으로 조회합니다."
    )
    @GetMapping("/api/v1/projects/{projectId}/approvals")
    public List<ApprovalResponse> getProjectApprovals(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        return approvalFacade.getProjectApprovals(ownerUserId, projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Operation(
            summary = "승인 상세 조회",
            description = "승인 요청 한 건의 유형(CHANGE/DEPLOYMENT/DOMAIN_BINDING/INFRA_OPERATION), " +
                          "상태, 연결된 taskId·요약을 조회합니다."
    )
    @GetMapping("/api/v1/approvals/{approvalId}")
    public ApprovalResponse getApproval(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long approvalId
    ) {
        return toResponse(approvalFacade.getApproval(ownerUserId, approvalId));
    }

    @Operation(
            summary = "Agent 작업 승인",
            description = "PENDING 승인을 APPROVED로 확정합니다. 해당 taskId의 모든 필요 승인이 완료되면 " +
                          "대기 중이던 Agent task가 자동으로 재개(QUEUED)됩니다. PENDING이 아닌 승인에 호출하면 409를 반환합니다.\n\n" +
                          "요청 본문은 선택입니다. REPOSITORY_BINDING 승인일 때만 `repositoryName`으로 " +
                          "생성·연결할 저장소 이름을 지정할 수 있고, 생략하면 승인 요약에 표시된 후보 이름이 쓰입니다."
    )
    @PostMapping("/api/v1/approvals/{approvalId}/approve")
    public ApprovalResponse approve(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long approvalId,
            @RequestBody(required = false) ApprovalDecisionRequest request
    ) {
        return toResponse(approvalFacade.approve(
                ownerUserId,
                approvalId,
                request == null ? null : request.repositoryName()
        ));
    }

    @Operation(
            summary = "Agent 작업 거절",
            description = "PENDING 승인을 REJECTED로 확정합니다. 해당 taskId를 취소시킵니다. " +
                          "PENDING이 아닌 승인에 호출하면 409를 반환합니다.\n\n" +
                          "단 REPOSITORY_BINDING 승인만 예외입니다 — 이미 성공한 CODE 작업물을 저장소에 남길지 묻는 승인이라 " +
                          "거절은 '저장소를 만들지 않는다'는 뜻일 뿐이고, task는 취소되지 않고 그대로 재개·완료됩니다."
    )
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
                ApprovalInputResponse.from(result.input()),
                result.createdAt(),
                result.decidedAt()
        );
    }
}
