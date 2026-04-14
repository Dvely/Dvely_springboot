package com.example.dvely.project.application.result;

import java.time.LocalDateTime;

public record ProjectDetailResult(
        Long projectId,
        String name,
        String status,
        String startMode,
        String templateType,
        String draftMode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
