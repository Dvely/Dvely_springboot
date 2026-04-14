package com.example.dvely.project.infrastructure.mapper;

import com.example.dvely.project.application.result.ActivityLogResult;
import com.example.dvely.project.application.result.CommitResult;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectOverviewResult;
import com.example.dvely.project.application.result.ProjectSummaryResult;
import com.example.dvely.project.application.result.RepositoryBindingResult;
import com.example.dvely.project.application.result.RepositoryHealthResult;
import com.example.dvely.project.presentation.dto.response.ProjectActivityLogResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCommitResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCreateResponse;
import com.example.dvely.project.presentation.dto.response.ProjectDetailResponse;
import com.example.dvely.project.presentation.dto.response.ProjectOverviewResponse;
import com.example.dvely.project.presentation.dto.response.RepositoryBindingResponse;
import com.example.dvely.project.presentation.dto.response.RepositoryHealthResponse;
import com.example.dvely.project.presentation.dto.response.ProjectSummaryResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class ProjectMapper {

    public ProjectCreateResponse toCreateResponse(ProjectDetailResult result) {
        return new ProjectCreateResponse(result.projectId(), result.name(), result.status());
    }

    public ProjectSummaryResponse toSummaryResponse(ProjectSummaryResult result) {
        return new ProjectSummaryResponse(
                result.projectId(),
                result.name(),
                result.deployStatus(),
                result.currentUrl(),
                result.updatedAt(),
                toRelativeText(result.updatedAt())
        );
    }

    public ProjectDetailResponse toDetailResponse(ProjectDetailResult result) {
        return new ProjectDetailResponse(
                result.projectId(),
                result.name(),
                result.status(),
                result.startMode(),
                result.templateType(),
                result.draftMode(),
                result.createdAt(),
                result.updatedAt()
        );
    }

    public ProjectOverviewResponse toOverviewResponse(ProjectOverviewResult result) {
        return new ProjectOverviewResponse(
                result.currentUrl(),
                result.deployStatus(),
                result.currentVersion(),
                result.recentChanges(),
                result.latestCommit() == null ? null : toCommitResponse(result.latestCommit()),
                result.trafficSummary(),
                toRepositoryHealthResponse(result.repositoryHealth()),
                result.domainSummary()
        );
    }

    public ProjectActivityLogResponse toActivityLogResponse(ActivityLogResult result) {
        return new ProjectActivityLogResponse(result.type(), result.message(), result.occurredAt());
    }

    public ProjectCommitResponse toCommitResponse(CommitResult result) {
        return new ProjectCommitResponse(result.sha(), result.message(), result.author(), result.committedAt());
    }

    public RepositoryBindingResponse toRepositoryBindingResponse(RepositoryBindingResult result) {
        return new RepositoryBindingResponse(
                result.sourceRepository(),
                result.deploymentRepository(),
                result.visibility(),
                result.status(),
                result.health()
        );
    }

    public RepositoryHealthResponse toRepositoryHealthResponse(RepositoryHealthResult result) {
        return new RepositoryHealthResponse(result.health());
    }

    private String toRelativeText(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return "방금 전";
        }

        Duration duration = Duration.between(updatedAt, LocalDateTime.now());
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return "방금 전";
        }
        if (minutes < 60) {
            return minutes + "분 전";
        }

        long hours = duration.toHours();
        if (hours < 24) {
            return hours + "시간 전";
        }

        return duration.toDays() + "일 전";
    }
}