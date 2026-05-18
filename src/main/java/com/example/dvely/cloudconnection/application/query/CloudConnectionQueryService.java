package com.example.dvely.cloudconnection.application.query;

import com.example.dvely.cloudconnection.application.result.CloudConnectionResult;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.common.exception.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CloudConnectionQueryService {

    private final CloudConnectionRepository cloudConnectionRepository;

    public List<CloudConnectionResult> getCloudConnections(Long ownerUserId) {
        return cloudConnectionRepository.findAllByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream()
                .map(this::toResult)
                .toList();
    }

    public CloudConnectionResult getCloudConnection(Long ownerUserId, Long cloudConnectionId) {
        return toResult(resolveCloudConnection(ownerUserId, cloudConnectionId));
    }

    private CloudConnection resolveCloudConnection(Long ownerUserId, Long cloudConnectionId) {
        return cloudConnectionRepository.findByIdAndOwnerUserId(cloudConnectionId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "클라우드 연결을 찾을 수 없습니다. cloudConnectionId=" + cloudConnectionId));
    }

    private CloudConnectionResult toResult(CloudConnection cloudConnection) {
        return new CloudConnectionResult(
                cloudConnection.getId(),
                cloudConnection.getProvider(),
                cloudConnection.getDisplayName(),
                cloudConnection.getAccountId(),
                cloudConnection.getRegion(),
                cloudConnection.getRoleArn(),
                cloudConnection.getAwsCredentialType(),
                cloudConnection.getAccessKeyId(),
                cloudConnection.getSecretAccessKey() != null,
                cloudConnection.getSessionToken() != null,
                cloudConnection.getGcpCredentialType(),
                cloudConnection.getServiceAccountKeyJson() != null,
                cloudConnection.getGcpProjectId(),
                cloudConnection.getServiceAccountEmail(),
                cloudConnection.getStatus(),
                cloudConnection.getLastCheckedAt(),
                cloudConnection.getCreatedAt(),
                cloudConnection.getUpdatedAt()
        );
    }
}
