package com.example.dvely.preview.application.result;

import java.time.LocalDateTime;

public record PreviewSessionInfo(
        String sessionId,
        Long ownerUserId,
        Long projectId,
        Long conversationId,
        String taskId,
        String containerId,
        String containerIp,
        String publicUrl,
        LocalDateTime expiresAt
) {
}
