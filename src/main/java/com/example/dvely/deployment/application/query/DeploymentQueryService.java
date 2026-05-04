package com.example.dvely.deployment.application.query;

import com.example.dvely.deployment.application.result.DeploymentCandidateResult;
import com.example.dvely.deployment.application.result.DeploymentHistoryResult;
import com.example.dvely.deployment.application.result.VersionDetailResult;
import com.example.dvely.deployment.application.result.VersionResult;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeploymentQueryService {

    private final DeploymentHistoryRepository deploymentHistoryRepository;

    @Transactional(readOnly = true)
    public List<DeploymentHistoryResult> getDeploymentHistories(Long projectId) {
        return deploymentHistoryRepository.findByProjectIdOrderByTriggeredAtDesc(projectId)
                .stream()
                .map(this::toResult)
                .toList();
    }

    private DeploymentHistoryResult toResult(DeploymentHistory h) {
        return new DeploymentHistoryResult(
                h.getId(),
                h.getProjectId(),
                h.getDeployTargetType().name(),
                h.getVersionLabel(),
                h.getDeployedUrl(),
                h.getStatus().name(),
                h.getTriggeredAt(),
                h.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<VersionResult> getVersions(Long ownerUserId, Long projectId) {
        // TODO: merge 기준 버전 목록 조회 구현
        return List.of();
    }

    @Transactional(readOnly = true)
    public VersionDetailResult getVersionDetail(Long ownerUserId, Long versionId) {
        // TODO: 버전 상세 조회 구현
        throw new UnsupportedOperationException("버전 상세 조회 미구현");
    }

    @Transactional(readOnly = true)
    public List<DeploymentCandidateResult> getDeploymentCandidates(Long ownerUserId, Long projectId) {
        // TODO: 배포 이력 중 LIVE, PREVIEW_READY 상태인 버전만 조회
        return List.of();
    }
}
