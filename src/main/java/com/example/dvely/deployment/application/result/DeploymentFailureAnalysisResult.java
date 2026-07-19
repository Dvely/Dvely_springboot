package com.example.dvely.deployment.application.result;

import java.time.LocalDateTime;

public record DeploymentFailureAnalysisResult(
        Long deploymentId,
        String summary,
        String logExcerpt,
        String suggestedFix,
        String analysisSource,
        LocalDateTime analyzedAt
) {
}
