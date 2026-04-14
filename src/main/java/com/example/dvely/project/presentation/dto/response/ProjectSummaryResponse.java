package com.example.dvely.project.presentation.dto.response;

import java.time.LocalDateTime;

public record ProjectSummaryResponse(
        Long projectId,
        String name,
        String deployStatus,
        String currentUrl,
        LocalDateTime updatedAt,
        String updatedAtRelativeText
) {
}
