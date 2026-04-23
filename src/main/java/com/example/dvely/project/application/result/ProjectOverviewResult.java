package com.example.dvely.project.application.result;

import java.util.List;

public record ProjectOverviewResult(
        String currentUrl,
        String deployStatus,
        String currentVersion,
        List<String> recentChanges,
        CommitResult latestCommit,
        String trafficSummary,
        RepositoryHealthResult repositoryHealth,
        String domainSummary
) {
}
