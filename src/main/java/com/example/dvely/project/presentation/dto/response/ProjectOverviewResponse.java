package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "프로젝트 개요 정보")
public record ProjectOverviewResponse(
        @Schema(description = "현재 배포 URL. 배포 전이면 null")
        String currentUrl,

        @Schema(description = "현재 배포 상태", allowableValues = {"DRAFT", "PENDING", "IN_PROGRESS", "PREVIEW_READY", "LIVE", "FAILED"}, example = "DRAFT")
        String deployStatus,

        @Schema(description = "현재 배포 버전")
        String currentVersion,

        @Schema(description = "최근 프로젝트 변경 요약")
        List<String> recentChanges,

        @Schema(description = "연결 저장소의 최신 커밋. 저장소가 없으면 null")
        ProjectCommitResponse latestCommit,

        @Schema(description = "트래픽 요약. 현재는 외부 지표 미연동 안내 문구")
        String trafficSummary,

        @Schema(description = "연결 저장소 health 요약")
        RepositoryHealthResponse repositoryHealth,

        @Schema(description = "도메인 요약. 현재는 관리형 도메인 미연동 안내 문구")
        String domainSummary
) {
}
