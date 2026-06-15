package com.example.dvely.preview.application.result;

import java.time.LocalDateTime;

public record PreviewSessionInfo(
        String sessionId,
        Long ownerUserId,
        Long projectId,
        Long conversationId,
        String taskId,
        String containerId,
        int hostPort,
        String publicUrl,
        LocalDateTime expiresAt
) {
}
