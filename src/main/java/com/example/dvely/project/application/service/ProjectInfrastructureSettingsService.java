package com.example.dvely.project.application.service;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.application.result.ProjectInfrastructureSettingsResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectInfrastructureSettingsService {

    private final ProjectRepository projectRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final ProjectCloudConnectionSettingRepository settingRepository;

    @Transactional(readOnly = true)
    public ProjectInfrastructureSettingsResult get(Long ownerUserId, Long projectId) {
        assertProjectOwner(ownerUserId, projectId);
        return settingRepository.findByProjectId(projectId)
                .map(setting -> toResult(
                        projectId,
                        resolveOwnedConnection(ownerUserId, setting.getCloudConnectionId()),
                        setting
                ))
                .orElseGet(() -> new ProjectInfrastructureSettingsResult(
                        projectId,
                        null,
                        null,
                        null,
                        null,
                        "NOT_CONFIGURED",
                        null,
                        null
                ));
    }

    @Transactional
    public ProjectInfrastructureSettingsResult select(Long ownerUserId,
                                                      Long projectId,
                                                      Long cloudConnectionId) {
        assertProjectOwner(ownerUserId, projectId);
        CloudConnection cloudConnection = resolveOwnedConnection(ownerUserId, cloudConnectionId);
        if (cloudConnection.getStatus() != CloudConnectionStatus.CONNECTED) {
            throw new IllegalStateException(
                    "실제 권한 확인이 완료된 CONNECTED 클라우드 연결만 프로젝트에 선택할 수 있습니다."
            );
        }
        ProjectCloudConnectionSetting setting = settingRepository.findByProjectId(projectId)
                .orElseGet(() -> new ProjectCloudConnectionSetting(projectId, cloudConnectionId));
        setting.select(cloudConnectionId);
        return toResult(projectId, cloudConnection, settingRepository.save(setting));
    }

    @Transactional
    public void clear(Long ownerUserId, Long projectId) {
        assertProjectOwner(ownerUserId, projectId);
        settingRepository.findByProjectId(projectId)
                .ifPresent(setting -> settingRepository.deleteByProjectId(projectId));
    }

    @Transactional(readOnly = true)
    public CloudConnection resolveConnectedConnection(Long ownerUserId, Long projectId) {
        assertProjectOwner(ownerUserId, projectId);
        ProjectCloudConnectionSetting setting = settingRepository.findByProjectId(projectId)
                .orElseThrow(() -> new IllegalStateException(
                        "프로젝트 Infrastructure 설정에서 클라우드 연결을 먼저 선택해야 합니다."
                ));
        CloudConnection cloudConnection = resolveOwnedConnection(ownerUserId, setting.getCloudConnectionId());
        if (cloudConnection.getStatus() != CloudConnectionStatus.CONNECTED) {
            throw new IllegalStateException("선택된 클라우드 연결의 실제 권한 확인 상태가 CONNECTED가 아닙니다.");
        }
        return cloudConnection;
    }

    private void assertProjectOwner(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
    }

    private CloudConnection resolveOwnedConnection(Long ownerUserId, Long cloudConnectionId) {
        return cloudConnectionRepository.findByIdAndOwnerUserId(cloudConnectionId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "클라우드 연결을 찾을 수 없습니다. cloudConnectionId=" + cloudConnectionId
                ));
    }

    private ProjectInfrastructureSettingsResult toResult(Long projectId,
                                                         CloudConnection cloudConnection,
                                                         ProjectCloudConnectionSetting setting) {
        return new ProjectInfrastructureSettingsResult(
                projectId,
                cloudConnection.getId(),
                cloudConnection.getProvider().name(),
                cloudConnection.getDisplayName(),
                cloudConnection.getRegion(),
                cloudConnection.getStatus().name(),
                cloudConnection.getLastCheckedAt(),
                setting.getUpdatedAt()
        );
    }
}
