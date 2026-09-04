package com.example.dvely.deployment.application.result;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @param deploymentId Pages/S3 는 배포 이력 id, EC2(독립 프론트 서버)는 대기 서버 id.
 * @param approvalIds  EC2 프론트 호스팅처럼 승인이 필요한 경우 그 승인 id 들(FE 가 승인 화면으로 연결).
 *                     Pages/S3 는 비어 있다.
 */
public record DeployResult(
        Long deploymentId,
        Long projectId,
        String deployTargetType,
        String versionName,
        String status,
        String pagesUrl,
        LocalDateTime createdAt,
        List<Long> approvalIds
) {
}
