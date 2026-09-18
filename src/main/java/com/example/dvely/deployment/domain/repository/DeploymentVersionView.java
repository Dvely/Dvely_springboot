package com.example.dvely.deployment.domain.repository;

import java.time.LocalDateTime;

/**
 * 버전 목록·배포 후보 전용 읽기 모델. U6(#341) 6-2.
 *
 * <p>{@code VersionResult} 와 {@code DeploymentCandidateResult} 가 함께 쓴다 — 두 응답이 쓰는 컬럼의
 * 합집합이고, 그 둘 말고는 아무것도 담지 않는다. 예전에는 두 조회가 프로젝트의 <b>전체</b> 이력을
 * 엔티티로 읽어 메모리에서 "version_label 있는 것"·"LIVE 인 것"을 골라냈다. 그 필터는 이제 SQL 에 있다.</p>
 */
public record DeploymentVersionView(
        Long id,
        String versionLabel,
        String commitSha,
        String title,
        String status,
        String deployedUrl,
        LocalDateTime triggeredAt,
        LocalDateTime mergedAt,
        LocalDateTime updatedAt
) {
}
