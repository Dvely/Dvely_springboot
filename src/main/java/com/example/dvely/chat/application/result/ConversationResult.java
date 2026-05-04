package com.example.dvely.chat.application.result;

import java.time.LocalDateTime;

public record ConversationResult(
        Long conversationId,
        Long projectId,
        boolean deleted,
        LocalDateTime deletedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
