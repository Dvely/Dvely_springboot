package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "프로젝트에 선택된 클라우드 연결. 아직 아무 연결도 선택하지 않았으면 cloudConnectionId·provider·"
        + "displayName·region·lastCheckedAt 은 null 이고, status 는 NOT_CONFIGURED 입니다(null 아님).")
public record ProjectInfrastructureSettingsResponse(
        @Schema(description = "프로젝트 ID") Long projectId,
        @Schema(description = "선택된 클라우드 연결 ID. 미선택 시 null", nullable = true) Long cloudConnectionId,
        @Schema(description = "클라우드 provider", allowableValues = {"AWS", "GCP"}, nullable = true) String provider,
        @Schema(description = "연결 이름", nullable = true) String displayName,
        @Schema(description = "리전", nullable = true) String region,

        // 이 status 는 클라우드 연결 status(CloudConnectionStatus, 9개)와 같지 않다. 연결이 선택돼
        // 있으면 그 연결의 status 를, 아무 연결도 선택돼 있지 않으면 NOT_CONFIGURED 를 준다. 즉 값
        // 집합이 상위집합이라 CloudConnectionStatus 를 그대로 재사용하면 안 된다 — allowableValues 에
        // NOT_CONFIGURED 가 빠져 있어 FE 가 미선택 응답을 통째로 파싱 실패하던 적이 있다(2026-09-01).
        @Schema(
                description = "인프라 설정 상태. 연결이 선택돼 있으면 그 연결 상태(CONNECTED 만 configuration 편집 가능), "
                        + "아무 연결도 선택돼 있지 않으면 NOT_CONFIGURED.",
                allowableValues = {"NOT_CONFIGURED", "VALIDATED", "VERIFYING", "CHECKING", "CONNECTED",
                        "PERMISSION_MISSING", "BILLING_DISABLED", "REGION_UNSUPPORTED", "INVALID_CREDENTIAL", "UNKNOWN_ERROR"},
                nullable = true
        )
        String status,

        @Schema(description = "연결의 마지막 상태 확인 시각", nullable = true) LocalDateTime lastCheckedAt,
        @Schema(description = "이 선택이 마지막으로 변경된 시각", nullable = true) LocalDateTime updatedAt
) {
}
