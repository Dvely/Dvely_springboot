package com.example.dvely.change.application.result;

import java.time.LocalDateTime;

public record ChangeResult(
        Long changeId,
        Long projectId,
        Long conversationId,
        String taskId,
        String previewSessionId,
        String status,
        String summary,
        Long approvalId,
        Integer prNumber,
        String mergeCommitSha,
        LocalDateTime mergedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
