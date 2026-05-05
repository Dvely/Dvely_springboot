package com.example.dvely.deployment.application.result;

import java.time.LocalDateTime;

public record DeploymentStatusResult(
        Long historyId,
        Long projectId,
        String deployTargetType,
        String versionLabel,
        String deployedUrl,

        // DB 상태: IN_PROGRESS | LIVE | FAILED
        String status,

        // GitHub Actions 실시간 빌드 상태 (IN_PROGRESS일 때만 유효)
        // queued | in_progress | completed
        String buildStatus,

        // GitHub Actions 빌드 결론 (completed일 때만 유효)
        // success | failure | cancelled | null
        String buildConclusion,

        LocalDateTime triggeredAt,
        LocalDateTime updatedAt
) {}
