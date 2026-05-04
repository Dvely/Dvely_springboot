package com.example.dvely.chat.presentation.dto;

import java.time.LocalDateTime;

public record ConversationResponse(
        Long conversationId,
        Long projectId,
        boolean deleted,
        LocalDateTime deletedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
