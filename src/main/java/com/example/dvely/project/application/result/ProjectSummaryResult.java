package com.example.dvely.project.application.result;

import java.time.LocalDateTime;

public record ProjectSummaryResult(
        Long projectId,
        String name,
        String deployStatus,
        String currentUrl,
        LocalDateTime updatedAt
) {
}
