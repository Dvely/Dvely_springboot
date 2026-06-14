package com.example.dvely.project.application.result;

import java.util.List;

public record ProjectOverviewResult(
        String currentUrl,
        String deployStatus,
        String currentVersion,
        String repositoryVersion,
        List<ActivityLogResult> recentChanges,
        CommitResult latestCommit,
        RepositoryHealthResult repositoryHealth,
        ProjectDomainSummaryResult domainSummary,
        ProjectCloudSummaryResult cloudSummary,
        List<ProjectOperationActionResult> operationActions
) {
}
