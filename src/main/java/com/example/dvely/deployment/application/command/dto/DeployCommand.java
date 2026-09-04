package com.example.dvely.deployment.application.command.dto;

import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.project.domain.value.FrontendHostingType;

/**
 * @param frontendHostingType 지정 시 프로젝트의 프론트 호스팅 설정을 이 값으로 바꾼다. null 이면 프로젝트의
 *                            현재 설정을 그대로 쓴다(재시도·에이전트 경로는 대개 null 로 넘겨 기존 설정 유지).
 */
public record DeployCommand(
        DeployTargetType deployTargetType,
        String versionName,
        String taskId,
        FrontendHostingType frontendHostingType
) {
    public DeployCommand(DeployTargetType deployTargetType, String versionName) {
        this(deployTargetType, versionName, null, null);
    }

    public DeployCommand(DeployTargetType deployTargetType, String versionName, String taskId) {
        this(deployTargetType, versionName, taskId, null);
    }

    public DeployCommand {
        if (deployTargetType == DeployTargetType.VERSION && (versionName == null || versionName.isBlank())) {
            throw new IllegalArgumentException("VERSION 배포 시 versionName은 필수입니다");
        }
    }
}
