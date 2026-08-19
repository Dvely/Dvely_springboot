package com.example.dvely.deployment.application.result;

import java.time.LocalDateTime;

public record DeploymentHistoryResult(
        Long historyId,
        Long projectId,
        String deployTargetType,
        String versionLabel,
        String deployedUrl,
        String status,

        // 실패 사유. 성공·진행 중이면 둘 다 null 이다. errorCode 는 분류, errorMessage 는 상세다.
        // 분류를 붙이기 전에 실패한 옛 이력은 errorCode 가 null 이고 errorMessage 만 있다.
        String errorCode,
        String errorMessage,

        LocalDateTime triggeredAt,
        LocalDateTime updatedAt,
        Long retriedFromHistoryId
) {}
