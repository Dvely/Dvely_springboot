package com.example.dvely.chat.application.result;

import java.time.LocalDateTime;

public record MessageResult(
        Long messageId,
        Long conversationId,
        String role,
        String content,
        long tokenCount,
        LocalDateTime createdAt
) {
}
