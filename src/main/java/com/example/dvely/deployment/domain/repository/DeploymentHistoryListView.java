package com.example.dvely.deployment.domain.repository;

import java.time.LocalDateTime;

/**
 * 배포 이력 목록 전용 읽기 모델. U6(#341) 6-2.
 *
 * <p>여기 있는 컬럼이 {@code DeploymentHistoryResult} 가 쓰는 전부다. 예전에는 이력 목록이 엔티티를
 * 통째로(28컬럼, TEXT 2개) 읽은 뒤 절반 넘게 버렸다 — 특히 {@code description TEXT} 는 목록 응답에
 * 없는데도 매 행 실려 왔다. 필드를 늘리기 전에 정말 응답에 나가는 값인지 확인할 것.</p>
 */
public record DeploymentHistoryListView(
        Long id,
        Long projectId,
        String deployTargetType,
        String versionLabel,
        String deployedUrl,
        String status,
        String failureCode,
        String errorMessage,
        LocalDateTime triggeredAt,
        LocalDateTime updatedAt,
        Long retriedFromHistoryId
) {
}
