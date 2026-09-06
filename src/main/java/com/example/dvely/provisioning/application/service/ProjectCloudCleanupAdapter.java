package com.example.dvely.provisioning.application.service;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.project.application.port.out.ProjectCloudCleanupPort;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.provisioning.infrastructure.S3StaticSiteStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link ProjectCloudCleanupPort} 구현 — 프로젝트의 프론트 S3 정적 사이트 버킷을 정리한다. 버킷 이름은
 * 프로젝트별로 결정적이라({@code qeploy-site-{account}-{region}-{projectId}}) 연결만 있으면 계산해
 * 삭제할 수 있다. 프로젝트가 S3 를 안 썼으면 그 버킷이 없어 {@link S3StaticSiteStore#deleteSite} 가
 * no-op 하므로, 호스팅 타입을 따로 확인하지 않아도 안전하다.
 *
 * <p>best-effort — 여기서 모든 예외를 삼킨다(정리 실패가 프로젝트 삭제를 되돌리면 안 된다). 연결이
 * 없으면 S3 를 아예 건드리지 않아, 클라우드 없는 대다수 프로젝트 삭제엔 부담이 없다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectCloudCleanupAdapter implements ProjectCloudCleanupPort {

    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final S3StaticSiteStore siteStore;

    @Override
    public void cleanupFrontendS3(Long projectId, Long ownerUserId) {
        try {
            cloudConnectionSettingRepository.findByProjectId(projectId)
                    .flatMap(setting -> cloudConnectionRepository
                            .findByIdAndOwnerUserId(setting.getCloudConnectionId(), ownerUserId))
                    .ifPresent(connection -> deleteSiteQuietly(connection, projectId));
        } catch (RuntimeException e) {
            log.warn("프론트 S3 사이트 정리 실패(무시): projectId={} 원인={}", projectId, e.getMessage());
        }
    }

    private void deleteSiteQuietly(CloudConnection connection, Long projectId) {
        String accountId = connection.getAccountId();
        if (accountId == null || accountId.isBlank()) {
            return;   // 계정 ID 없으면 버킷 이름을 못 짓는다(애초에 그런 버킷도 없다)
        }
        siteStore.deleteSite(connection, siteStore.bucketNameFor(connection, projectId));
    }
}
