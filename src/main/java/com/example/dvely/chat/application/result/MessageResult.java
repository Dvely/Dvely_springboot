package com.example.dvely.chat.application.result;

import java.time.LocalDateTime;

public record MessageResult(
        Long messageId,
        Long conversationId,
        String role,
        String content,
        long tokenCount,
        LocalDateTime createdAt,
        // Present only right after ChatCommandService.sendMessage() successfully submits an
        // Agent plan (see AgentOrchestrator.submit()); null for historical reads (ChatQueryService)
        // and for sendMessage calls where the Decision Agent failed before a task was created.
        String taskId
) {
}
