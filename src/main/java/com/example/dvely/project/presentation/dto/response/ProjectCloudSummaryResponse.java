package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Overview 프로젝트 클라우드 연결 요약")
public record ProjectCloudSummaryResponse(
        @Schema(description = "CONNECTED 클라우드 연결이 선택되어 있는지 여부. false면 이하 필드는 모두 null") boolean configured,
        @Schema(description = "선택된 클라우드 연결 ID. 미설정 시 null", nullable = true) Long cloudConnectionId,
        @Schema(description = "클라우드 provider", allowableValues = {"AWS", "GCP"}, nullable = true) String provider,
        @Schema(description = "연결 이름", nullable = true) String displayName,
        @Schema(description = "리전", nullable = true) String region,

        @Schema(
                description = "연결 상태",
                allowableValues = {"VALIDATED", "VERIFYING", "CHECKING", "CONNECTED", "PERMISSION_MISSING", "BILLING_DISABLED", "REGION_UNSUPPORTED", "INVALID_CREDENTIAL", "UNKNOWN_ERROR"},
                nullable = true
        )
        String status,

        @Schema(description = "마지막 상태 확인 시각", nullable = true) LocalDateTime lastCheckedAt
) {
}
