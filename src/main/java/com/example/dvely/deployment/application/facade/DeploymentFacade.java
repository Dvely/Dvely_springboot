package com.example.dvely.deployment.application.facade;

import com.example.dvely.deployment.application.command.DeploymentCommandService;
import com.example.dvely.deployment.application.command.dto.DeployCommand;
import com.example.dvely.deployment.application.query.DeploymentQueryService;
import com.example.dvely.deployment.application.result.DeploymentCandidateResult;
import com.example.dvely.deployment.application.result.DeploymentHistoryResult;
import com.example.dvely.deployment.application.result.DeploymentLogsResult;
import com.example.dvely.deployment.application.result.DeploymentStatusResult;
import com.example.dvely.deployment.application.result.DeployResult;
import com.example.dvely.deployment.application.result.VersionDetailResult;
import com.example.dvely.deployment.application.result.VersionResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeploymentFacade {

    private final DeploymentCommandService deploymentCommandService;
    private final DeploymentQueryService deploymentQueryService;

    public DeployResult deploy(Long ownerUserId, Long projectId, DeployCommand command) {
        return deploymentCommandService.deploy(ownerUserId, projectId, command);
    }

    public List<DeploymentHistoryResult> getDeploymentHistories(Long projectId) {
        return deploymentQueryService.getDeploymentHistories(projectId);
    }

    public DeploymentStatusResult getDeploymentStatus(Long historyId) {
        return deploymentQueryService.getDeploymentStatus(historyId);
    }

    public List<VersionResult> getVersions(Long ownerUserId, Long projectId) {
        return deploymentQueryService.getVersions(ownerUserId, projectId);
    }

    public VersionDetailResult getVersionDetail(Long ownerUserId, Long versionId) {
        return deploymentQueryService.getVersionDetail(ownerUserId, versionId);
    }

    public List<DeploymentCandidateResult> getDeploymentCandidates(Long ownerUserId, Long projectId) {
        return deploymentQueryService.getDeploymentCandidates(ownerUserId, projectId);
    }

    public DeploymentLogsResult getDeploymentLogs(Long historyId) {
        return deploymentQueryService.getDeploymentLogs(historyId);
    }
}
