package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "인프라 설정 변경 이력 한 건 (BI-129) — PENDING_APPROVAL·REJECTED를 포함한 모든 상태")
public record ProjectInfrastructureChangeResponse(
        @Schema(description = "변경 이력 ID", example = "12")
        Long changeId,

        @Schema(description = "최초 저장인지 변경인지", allowableValues = {"CREATED", "UPDATED"}, example = "UPDATED")
        String action,

        @Schema(description = "변경 처리 상태", allowableValues = {"APPLIED", "PENDING_APPROVAL", "REJECTED"}, example = "APPLIED")
        String status,

        @Schema(description = "요청 시점 배포 아키텍처", allowableValues = {"SERVER", "CONTAINER", "SERVERLESS"}, example = "CONTAINER")
        String deploymentArchitecture,

        @Schema(description = "요청 시점 컴퓨팅 티어", allowableValues = {"MICRO", "SMALL", "MEDIUM", "LARGE"}, example = "SMALL")
        String computeTier,

        @Schema(description = "요청 시점 스토리지 종류", allowableValues = {"NONE", "OBJECT_STORAGE"}, example = "OBJECT_STORAGE")
        String storageType,

        @Schema(description = "요청 시점 네트워크 공개 범위", allowableValues = {"PUBLIC", "PRIVATE"}, example = "PUBLIC")
        String networkAccess,

        @Schema(description = "이 변경을 게이트한 Approval ID. 즉시 적용된 변경은 null", example = "34")
        Long approvalId,

        @Schema(description = "변경을 요청한 사용자 ID", example = "7")
        Long actorUserId,

        @Schema(description = "변경 요청 시각")
        LocalDateTime createdAt,

        @Schema(description = "APPLIED/REJECTED로 확정된 시각. 대기 중이면 null")
        LocalDateTime decidedAt
) {
}
