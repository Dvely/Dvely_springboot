package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "프로젝트에 선택된 클라우드 연결. 아직 아무 연결도 선택하지 않았으면 cloudConnectionId 이하 필드가 모두 null입니다.")
public record ProjectInfrastructureSettingsResponse(
        @Schema(description = "프로젝트 ID") Long projectId,
        @Schema(description = "선택된 클라우드 연결 ID. 미선택 시 null", nullable = true) Long cloudConnectionId,
        @Schema(description = "클라우드 provider", allowableValues = {"AWS", "GCP"}, nullable = true) String provider,
        @Schema(description = "연결 이름", nullable = true) String displayName,
        @Schema(description = "리전", nullable = true) String region,

        @Schema(
                description = "연결 상태. CONNECTED만 인프라 설정(configuration) 편집 가능",
                allowableValues = {"VALIDATED", "VERIFYING", "CHECKING", "CONNECTED", "PERMISSION_MISSING", "BILLING_DISABLED", "REGION_UNSUPPORTED", "INVALID_CREDENTIAL", "UNKNOWN_ERROR"},
                nullable = true
        )
        String status,

        @Schema(description = "연결의 마지막 상태 확인 시각", nullable = true) LocalDateTime lastCheckedAt,
        @Schema(description = "이 선택이 마지막으로 변경된 시각", nullable = true) LocalDateTime updatedAt
) {
}
