package com.example.dvely.deployment.application.facade;

import com.example.dvely.deployment.application.query.DeploymentQueryService;
import com.example.dvely.deployment.application.result.VersionResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeploymentFacade {

    private final DeploymentQueryService deploymentQueryService;

    public List<VersionResult> getVersions(Long ownerUserId, Long projectId) {
        return deploymentQueryService.getVersions(ownerUserId, projectId);
    }
}
