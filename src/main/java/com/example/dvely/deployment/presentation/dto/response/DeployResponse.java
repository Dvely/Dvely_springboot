package com.example.dvely.deployment.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "배포 요청 결과")
public record DeployResponse(
        @Schema(description = "배포 ID. Pages/S3는 배포 이력 ID, EC2 프론트는 대기 서버 ID") Long deploymentId,
        @Schema(description = "프로젝트 ID") Long projectId,
        @Schema(description = "배포 기준 타입 (LATEST, VERSION)") String deployTargetType,
        @Schema(description = "요청한 버전명. LATEST는 worker가 tag를 확정하기 전까지 null") String versionName,
        @Schema(description = "배포 진행 상태 (PENDING, IN_PROGRESS, LIVE, FAILED)") String status,
        @Schema(description = "GitHub Pages URL. worker가 배포 준비를 마치기 전은 null") String pagesUrl,
        @Schema(description = "배포 요청 시각") LocalDateTime createdAt,
        @Schema(description = "승인 ID 목록. EC2 프론트 호스팅처럼 승인이 필요하면 채워진다(승인 화면으로 연결). "
                + "Pages/S3는 비어 있음") List<Long> approvalIds
) {
}
