package com.example.dvely.project.infrastructure.mapper;

import com.example.dvely.project.application.result.ActivityLogResult;
import com.example.dvely.project.application.result.CommitResult;
import com.example.dvely.project.application.result.GithubRepositoryResult;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectOverviewResult;
import com.example.dvely.project.application.result.ProjectRepositoryResult;
import com.example.dvely.project.application.result.ProjectSummaryResult;
import com.example.dvely.project.application.result.RepositoryHealthResult;
import com.example.dvely.project.presentation.dto.response.ProjectActivityLogResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCloudSummaryResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCommitResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCreateResponse;
import com.example.dvely.project.presentation.dto.response.ProjectDetailResponse;
import com.example.dvely.project.presentation.dto.response.ProjectDomainSummaryResponse;
import com.example.dvely.project.presentation.dto.response.GithubRepositoryResponse;
import com.example.dvely.project.presentation.dto.response.ProjectOperationActionResponse;
import com.example.dvely.project.presentation.dto.response.ProjectOverviewResponse;
import com.example.dvely.project.presentation.dto.response.ProjectRepositoryResponse;
import com.example.dvely.project.presentation.dto.response.RepositoryHealthResponse;
import com.example.dvely.project.presentation.dto.response.ProjectSummaryResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

@Component
public class ProjectMapper {

    public ProjectCreateResponse toCreateResponse(ProjectDetailResult result) {
        return new ProjectCreateResponse(result.projectId(), result.name(), result.status());
    }

    public GithubRepositoryResponse toGithubRepositoryResponse(GithubRepositoryResult result) {
        return new GithubRepositoryResponse(
                result.fullName(),
                result.name(),
                result.owner(),
                result.description(),
                result.visibility(),
                result.defaultBranch(),
                result.updatedAt()
        );
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

    public ProjectRepositoryResponse toProjectRepositoryResponse(ProjectRepositoryResult result) {
        return new ProjectRepositoryResponse(
                result.projectId(),
                result.repositoryFullName(),
                result.repositoryVisibility(),
                result.bindingStatus(),
                result.repositoryHealth()
        );
    }

    public ProjectOverviewResponse toOverviewResponse(ProjectOverviewResult result) {
        return new ProjectOverviewResponse(
                result.currentUrl(),
                result.deployStatus(),
                result.currentVersion(),
                result.repositoryVersion(),
                result.recentChanges().stream().map(this::toActivityLogResponse).toList(),
                result.latestCommit() == null ? null : toCommitResponse(result.latestCommit()),
                toRepositoryHealthResponse(result.repositoryHealth()),
                result.domainSummary() == null
                        ? null
                        : new ProjectDomainSummaryResponse(
                                result.domainSummary().domainId(),
                                result.domainSummary().hostname(),
                                result.domainSummary().url(),
                                result.domainSummary().type(),
                                result.domainSummary().hostingTarget(),
                                result.domainSummary().status(),
                                result.domainSummary().httpsEnforced(),
                                result.domainSummary().certificateStatus(),
                                result.domainSummary().certificateExpiresAt(),
                                result.domainSummary().lastCheckedAt()
                        ),
                new ProjectCloudSummaryResponse(
                        result.cloudSummary().configured(),
                        result.cloudSummary().cloudConnectionId(),
                        result.cloudSummary().provider(),
                        result.cloudSummary().displayName(),
                        result.cloudSummary().region(),
                        result.cloudSummary().status(),
                        result.cloudSummary().lastCheckedAt()
                ),
                result.operationActions().stream()
                        .map(action -> new ProjectOperationActionResponse(
                                action.type(),
                                action.available(),
                                action.reason()
                        ))
                        .toList()
        );
    }

    public ProjectActivityLogResponse toActivityLogResponse(ActivityLogResult result) {
        return new ProjectActivityLogResponse(result.type(), result.message(), result.occurredAt());
    }

    public ProjectCommitResponse toCommitResponse(CommitResult result) {
        return new ProjectCommitResponse(
                result.sha(),
                result.message(),
                result.author(),
                result.committedAt(),
                toRelativeText(result.committedAt())
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
        return toRelativeText(duration);
    }

    private String toRelativeText(OffsetDateTime occurredAt) {
        if (occurredAt == null) {
            return null;
        }
        return toRelativeText(Duration.between(occurredAt, OffsetDateTime.now()));
    }

    private String toRelativeText(Duration duration) {
        if (duration.isNegative()) {
            return "방금 전";
        }
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
