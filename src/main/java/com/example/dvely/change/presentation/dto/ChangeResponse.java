package com.example.dvely.change.presentation.dto;

import com.example.dvely.change.application.result.ChangeResult;
import java.time.LocalDateTime;

public record ChangeResponse(
        Long changeId,
        Long projectId,
        Long conversationId,
        String taskId,
        String previewSessionId,
        String status,
        String summary,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ChangeResponse from(ChangeResult result) {
        return new ChangeResponse(
                result.changeId(),
                result.projectId(),
                result.conversationId(),
                result.taskId(),
                result.previewSessionId(),
                result.status(),
                result.summary(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}
