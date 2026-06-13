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

        @Schema(description = "Deployment, Change, Approval, Domain의 최근 이벤트 3개")
        List<ProjectActivityLogResponse> recentChanges,

        @Schema(description = "연결 저장소의 최신 커밋. 저장소가 없으면 null")
        ProjectCommitResponse latestCommit,

        @Schema(description = "연결 저장소 health 요약")
        RepositoryHealthResponse repositoryHealth,

        @Schema(description = "현재 우선 도메인 요약. 연결된 도메인이 없으면 null")
        ProjectDomainSummaryResponse domainSummary,

        @Schema(description = "프로젝트에 선택된 클라우드 연결 상태")
        ProjectCloudSummaryResponse cloudSummary,

        @Schema(description = "현재 프로젝트 상태에 따른 운영 조치")
        List<ProjectOperationActionResponse> operationActions
) {
}
