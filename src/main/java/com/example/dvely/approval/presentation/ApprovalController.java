package com.example.dvely.approval.presentation;

import com.example.dvely.approval.application.facade.ApprovalFacade;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.presentation.dto.ApprovalDecisionRequest;
import com.example.dvely.approval.presentation.dto.ApprovalInputResponse;
import com.example.dvely.approval.presentation.dto.ApprovalRejectRequest;
import com.example.dvely.change.application.result.ResultScope;
import com.example.dvely.approval.presentation.dto.ApprovalResponse;
import com.example.dvely.common.paging.CursorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Approval", description = "Agent 작업 승인 및 거절 API")
@RestController
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalFacade approvalFacade;

    @Operation(
            summary = "프로젝트 승인 목록 조회",
            description = "프로젝트에서 생성된 승인 요청을 상태(PENDING/APPROVED/REJECTED/CANCELLED)와 " +
                          "무관하게 최신순으로 조회합니다. limit 기본 200, 최대 500(초과 시 500으로 보정). " +
                          "더 오래된 것이 남아 있으면 응답 헤더 X-Qeploy-Next-Cursor 에 다음 커서가 실리며, " +
                          "after 로 넘겨 이어 받습니다."
    )
    @GetMapping("/api/v1/projects/{projectId}/approvals")
    public ResponseEntity<List<ApprovalResponse>> getProjectApprovals(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after
    ) {
        return CursorResponse.of(
                approvalFacade.getProjectApprovals(ownerUserId, projectId, limit, after)
                        .map(this::toResponse));
    }

    @Operation(
            summary = "승인 상세 조회",
            description = "승인 요청 한 건의 유형(값 목록은 ApprovalResponse.type), " +
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
                          "RESULT 승인은 본문 `option`(KEEP 기본 | REVERT)을 받습니다. REVERT 는 preview 를 main 과 같은 상태로 " +
                          "되돌리는 커밋을 먼저 올리고 거절합니다. 그 환경에서 쓸 수 없으면 409 RESULT_REVERT_UNAVAILABLE, " +
                          "올리지 못하면 502 RESULT_REVERT_FAILED 이며 둘 다 승인은 PENDING 그대로입니다.\n\n" +
                          "단 REPOSITORY_BINDING 승인만 예외입니다 — 이미 성공한 CODE 작업물을 저장소에 남길지 묻는 승인이라 " +
                          "거절은 '저장소를 만들지 않는다'는 뜻일 뿐이고, task는 취소되지 않고 그대로 재개·완료됩니다."
    )
    @PostMapping("/api/v1/approvals/{approvalId}/reject")
    public ApprovalResponse reject(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long approvalId,
            @RequestBody(required = false) ApprovalRejectRequest request
    ) {
        return toResponse(approvalFacade.reject(ownerUserId, approvalId, request == null ? null : request.option()));
    }

    @Operation(
            summary = "결과 승인 반영 범위 조회",
            description = "RESULT 승인이 main 에 실제로 반영할 범위(main...preview)를 서버가 계산해 돌려줍니다. 이번 작업 외의 " +
                          "변경(다른 작업·이전에 거절한 작업·직접 올린 커밋)이 함께 반영되는지를 보여 주며, 화면 문구(notice, " +
                          "rejectOptions)도 서버가 만듭니다. PENDING 이 아니면 결정 당시 기록을 돌려줍니다(scopeState=DECIDED). " +
                          "범위 계산에 실패해도 200 이며 scopeState=UNAVAILABLE 입니다. RESULT 가 아닌 승인은 400."
    )
    @GetMapping("/api/v1/approvals/{approvalId}/result-scope")
    public ResultScope getResultScope(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long approvalId
    ) {
        return approvalFacade.getResultScope(ownerUserId, approvalId);
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
                result.decidedAt(),
                result.rejectOption()
        );
    }
}
