package com.example.dvely.cloudconnection.application.query;

import com.example.dvely.cloudconnection.application.result.CloudConnectionHealthResult;
import com.example.dvely.cloudconnection.application.result.CloudConnectionResult;
import com.example.dvely.cloudconnection.application.result.CloudConnectionVerificationJobResult;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.model.CloudConnectionVerificationJob;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionSummaryView;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionVerificationJobRepository;
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
    private final CloudConnectionVerificationJobRepository verificationJobRepository;

    public List<CloudConnectionResult> getCloudConnections(Long ownerUserId) {
        return cloudConnectionRepository.findSummariesByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream()
                .map(CloudConnectionQueryService::toResult)
                .toList();
    }

    /**
     * 읽기 모델 → 응답. 비밀 세 개는 뷰가 이미 boolean 으로만 들고 있어, 여기서 평문을 볼 방법이
     * 없다 — 예전의 "평문을 읽어 != null 로 바꾼 뒤 버린다" 를 구조적으로 대체한다.
     */
    private static CloudConnectionResult toResult(CloudConnectionSummaryView view) {
        return new CloudConnectionResult(
                view.id(),
                CloudProvider.valueOf(view.provider()),
                view.displayName(),
                view.accountId(),
                view.region(),
                view.roleArn(),
                view.awsCredentialType(),
                view.accessKeyId(),
                view.secretAccessKeyConfigured(),
                view.sessionTokenConfigured(),
                view.gcpCredentialType(),
                view.serviceAccountKeyConfigured(),
                view.gcpProjectId(),
                view.serviceAccountEmail(),
                CloudConnectionStatus.valueOf(view.status()),
                view.lastCheckedAt(),
                view.createdAt(),
                view.updatedAt()
        );
    }

    public CloudConnectionResult getCloudConnection(Long ownerUserId, Long cloudConnectionId) {
        return toResult(resolveCloudConnection(ownerUserId, cloudConnectionId));
    }

    public CloudConnectionHealthResult getHealth(Long ownerUserId, Long cloudConnectionId) {
        CloudConnection cloudConnection = resolveCloudConnection(ownerUserId, cloudConnectionId);
        String message = verificationJobRepository.findLatestByCloudConnectionId(cloudConnectionId)
                .map(CloudConnectionVerificationJob::getMessage)
                .orElse("실제 클라우드 권한 확인 Job이 아직 없습니다.");
        return new CloudConnectionHealthResult(
                cloudConnection.getId(),
                cloudConnection.getProvider(),
                cloudConnection.getStatus(),
                message,
                cloudConnection.getLastCheckedAt()
        );
    }

    public CloudConnectionVerificationJobResult getVerificationJob(Long ownerUserId, String jobId) {
        CloudConnectionVerificationJob job = verificationJobRepository.findByIdAndOwnerUserId(jobId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("클라우드 연결 확인 Job을 찾을 수 없습니다. jobId=" + jobId));
        return toJobResult(job);
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

    private CloudConnectionVerificationJobResult toJobResult(CloudConnectionVerificationJob job) {
        return new CloudConnectionVerificationJobResult(
                job.getId(),
                job.getCloudConnectionId(),
                job.getStatus(),
                job.getConnectionStatus(),
                job.getMessage(),
                job.getAttempt(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }
}
