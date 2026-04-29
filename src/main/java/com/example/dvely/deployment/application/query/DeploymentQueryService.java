package com.example.dvely.deployment.application.query;

import com.example.dvely.deployment.application.result.VersionResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeploymentQueryService {

    @Transactional(readOnly = true)
    public List<VersionResult> getVersions(Long ownerUserId, Long projectId) {
        // TODO: merge 기준 버전 목록 조회 구현
        return List.of();
    }
}
