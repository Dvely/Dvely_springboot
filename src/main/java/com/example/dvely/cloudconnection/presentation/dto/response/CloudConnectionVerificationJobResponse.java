package com.example.dvely.cloudconnection.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "클라우드 연결 실 권한 검증 Job 상태")
public record CloudConnectionVerificationJobResponse(
        @Schema(description = "Job ID") String jobId,
        @Schema(description = "검증 대상 클라우드 연결 ID") Long cloudConnectionId,

        @Schema(description = "Job 진행 상태", allowableValues = {"PENDING", "RUNNING", "SUCCEEDED", "FAILED"}, example = "SUCCEEDED")
        String status,

        @Schema(
                description = "Job이 산출한 최종 연결 상태(status가 SUCCEEDED/FAILED일 때 의미 있음). " +
                              "CONNECTED | PERMISSION_MISSING | BILLING_DISABLED | REGION_UNSUPPORTED | INVALID_CREDENTIAL | UNKNOWN_ERROR",
                example = "CONNECTED"
        )
        String connectionStatus,

        @Schema(description = "검증 결과 설명") String message,
        @Schema(description = "재시도 횟수(1부터 시작)") int attempt,
        @Schema(description = "Job 생성 시각") LocalDateTime createdAt,
        @Schema(description = "Job 실행 시작 시각. 아직 시작 전이면 null") LocalDateTime startedAt,
        @Schema(description = "Job 완료 시각. 아직 진행 중이면 null") LocalDateTime completedAt
) {
}
