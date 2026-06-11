package com.example.dvely.cloudconnection.infrastructure.persistence.repository;

import com.example.dvely.cloudconnection.domain.model.CloudConnectionVerificationJob;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionVerificationJobRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionVerificationJobStatus;
import com.example.dvely.cloudconnection.infrastructure.persistence.entity.CloudConnectionVerificationJobEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class CloudConnectionVerificationJobRepositoryAdapter
        implements CloudConnectionVerificationJobRepository {

    private final SpringDataCloudConnectionVerificationJobRepository springDataRepository;

    @Override
    public CloudConnectionVerificationJob save(CloudConnectionVerificationJob job) {
        CloudConnectionVerificationJobEntity entity = springDataRepository.findById(job.getId())
                .orElseGet(() -> CloudConnectionVerificationJobEntity.from(job));
        entity.updateFrom(job);
        return springDataRepository.save(entity).toDomain();
    }

    @Override
    public Optional<CloudConnectionVerificationJob> findById(String jobId) {
        return springDataRepository.findById(jobId).map(CloudConnectionVerificationJobEntity::toDomain);
    }

    @Override
    public Optional<CloudConnectionVerificationJob> findByIdAndOwnerUserId(String jobId, Long ownerUserId) {
        return springDataRepository.findByIdAndOwnerUserId(jobId, ownerUserId)
                .map(CloudConnectionVerificationJobEntity::toDomain);
    }

    @Override
    public Optional<CloudConnectionVerificationJob> findLatestByCloudConnectionId(Long cloudConnectionId) {
        return springDataRepository.findFirstByCloudConnectionIdOrderByCreatedAtDesc(cloudConnectionId)
                .map(CloudConnectionVerificationJobEntity::toDomain);
    }

    @Override
    @Transactional
    public List<String> claimPending(String workerId, int limit) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plusMinutes(2);
        return springDataRepository.findPendingIds(
                        CloudConnectionVerificationJobStatus.PENDING.name(),
                        PageRequest.of(0, limit)
                )
                .stream()
                .filter(jobId -> springDataRepository.claim(
                        jobId,
                        workerId,
                        leaseUntil,
                        now,
                        CloudConnectionVerificationJobStatus.PENDING.name(),
                        CloudConnectionVerificationJobStatus.RUNNING.name(),
                        CloudConnectionStatus.VERIFYING.name(),
                        "실제 클라우드 API로 권한을 확인하고 있습니다."
                ) == 1)
                .toList();
    }

    @Override
    @Transactional
    public void recoverExpiredLeases() {
        springDataRepository.findByStatusAndLeaseUntilBefore(
                        CloudConnectionVerificationJobStatus.RUNNING.name(),
                        LocalDateTime.now()
                )
                .forEach(entity -> {
                    CloudConnectionVerificationJob job = entity.toDomain();
                    job.retryAfterExpiredLease();
                    entity.updateFrom(job);
                });
    }
}
