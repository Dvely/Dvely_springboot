package com.example.dvely.deployment.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "배포 요청 결과")
public record DeployResponse(
        @Schema(description = "배포 ID") Long deploymentId,
        @Schema(description = "프로젝트 ID") Long projectId,
        @Schema(description = "배포 기준 타입 (LATEST, VERSION)") String deployTargetType,
        @Schema(description = "요청한 버전명. LATEST는 worker가 tag를 확정하기 전까지 null") String versionName,
        @Schema(description = "배포 진행 상태 (PENDING, IN_PROGRESS, LIVE, FAILED)") String status,
        @Schema(description = "GitHub Pages URL. worker가 배포 준비를 마치기 전은 null") String pagesUrl,
        @Schema(description = "배포 요청 시각") LocalDateTime createdAt
) {
}
