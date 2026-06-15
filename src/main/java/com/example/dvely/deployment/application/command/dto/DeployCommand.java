package com.example.dvely.deployment.application.command.dto;

import com.example.dvely.deployment.domain.value.DeployTargetType;

public record DeployCommand(
        DeployTargetType deployTargetType,
        String versionName,
        String taskId
) {
    public DeployCommand(DeployTargetType deployTargetType, String versionName) {
        this(deployTargetType, versionName, null);
    }

    public DeployCommand {
        if (deployTargetType == DeployTargetType.VERSION && (versionName == null || versionName.isBlank())) {
            throw new IllegalArgumentException("VERSION 배포 시 versionName은 필수입니다");
        }
    }
}
