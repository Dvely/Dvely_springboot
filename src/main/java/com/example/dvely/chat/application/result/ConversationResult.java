package com.example.dvely.chat.application.result;

import java.time.LocalDateTime;

public record ConversationResult(
        Long conversationId,
        Long projectId,
        String title,
        String projectName,
        boolean deleted,
        LocalDateTime deletedAt,
        LocalDateTime retentionExpiresAt,
        Integer remainingRetentionDays,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public ConversationResult(Long conversationId,
                              Long projectId,
                              boolean deleted,
                              LocalDateTime deletedAt,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt) {
        this(
                conversationId,
                projectId,
                null,
                null,
                deleted,
                deletedAt,
                null,
                null,
                createdAt,
                updatedAt
        );
    }
}
