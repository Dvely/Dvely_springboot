package com.example.dvely.project.presentation.dto.response;

import java.util.List;

public record ProjectOverviewResponse(
        String currentUrl,
        String deployStatus,
        String currentVersion,
        List<String> recentChanges,
        ProjectCommitResponse latestCommit,
        String trafficSummary,
        RepositoryHealthResponse repositoryHealth,
        String domainSummary
) {
}
