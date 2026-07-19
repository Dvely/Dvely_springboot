package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "프로젝트 인프라 설정 조회/저장 응답 (GET·PUT 공통 shape)")
public record ProjectInfrastructureConfigurationResponse(
        @Schema(description = "프로젝트 ID", example = "1")
        Long projectId,

        @Schema(description = "CONNECTED 상태의 클라우드 연결이 선택되어 있어 설정을 편집할 수 있는지 여부", example = "true")
        boolean configurable,

        @Schema(description = "현재 적용된 설정. 한 번도 저장한 적 없으면 null")
        Settings settings,

        @Schema(description = "승인 대기 중인 변경. 없으면 null")
        PendingChange pendingChange
) {

    @Schema(description = "현재 적용된 인프라 설정")
    public record Settings(
            @Schema(description = "배포 아키텍처", allowableValues = {"SERVER", "CONTAINER", "SERVERLESS"}, example = "CONTAINER")
            String deploymentArchitecture,

            @Schema(description = "컴퓨팅 티어", allowableValues = {"MICRO", "SMALL", "MEDIUM", "LARGE"}, example = "SMALL")
            String computeTier,

            @Schema(description = "스토리지 종류", allowableValues = {"NONE", "OBJECT_STORAGE"}, example = "OBJECT_STORAGE")
            String storageType,

            @Schema(description = "네트워크 공개 범위", allowableValues = {"PUBLIC", "PRIVATE"}, example = "PUBLIC")
            String networkAccess,

            @Schema(description = "설정이 마지막으로 적용된 시각")
            LocalDateTime updatedAt
    ) {
    }

    @Schema(description = "승인 대기 중인 인프라 설정 변경")
    public record PendingChange(
            @Schema(description = "변경 이력 ID", example = "12")
            Long changeId,

            @Schema(description = "이 변경을 게이트하는 Approval ID", example = "34")
            Long approvalId,

            @Schema(description = "최초 저장인지 변경인지", allowableValues = {"CREATED", "UPDATED"}, example = "UPDATED")
            String action,

            @Schema(description = "요청된 배포 아키텍처", allowableValues = {"SERVER", "CONTAINER", "SERVERLESS"}, example = "SERVERLESS")
            String deploymentArchitecture,

            @Schema(description = "요청된 컴퓨팅 티어", allowableValues = {"MICRO", "SMALL", "MEDIUM", "LARGE"}, example = "MICRO")
            String computeTier,

            @Schema(description = "요청된 스토리지 종류", allowableValues = {"NONE", "OBJECT_STORAGE"}, example = "NONE")
            String storageType,

            @Schema(description = "요청된 네트워크 공개 범위", allowableValues = {"PUBLIC", "PRIVATE"}, example = "PUBLIC")
            String networkAccess,

            @Schema(description = "변경 요청 시각")
            LocalDateTime createdAt
    ) {
    }
}
