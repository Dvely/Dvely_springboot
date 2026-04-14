package com.example.dvely.project.presentation.dto.response;

import java.time.LocalDateTime;

public record ProjectDetailResponse(
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
