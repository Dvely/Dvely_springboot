package com.example.dvely.domainbinding.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "도메인 연결/해제 요청 접수 응답. 실제 처리는 Agent task로 비동기 실행되므로 " +
        "GET /api/v1/agent/tasks/{taskId}로 완료 여부를 폴링한 뒤 GET 도메인 API로 확정된 결과를 조회하세요.")
public record DomainBindingSubmissionResponse(
        @Schema(description = "비동기 Agent task ID") String taskId,
        @Schema(description = "task 현재 상태", example = "WAITING_APPROVAL") String status,
        @Schema(description = "Domain Approval 정책이 켜져 있을 때 생성된 승인 ID 목록. 정책이 꺼져 있으면 빈 배열") List<Long> approvalIds
) {
}
