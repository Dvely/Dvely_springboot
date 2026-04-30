package com.example.dvely.chat.presentation.dto;

import java.time.LocalDateTime;

public record MessageResponse(
        Long messageId,
        Long conversationId,
        String role,
        String content,
        long tokenCount,
        LocalDateTime createdAt
) {
}
