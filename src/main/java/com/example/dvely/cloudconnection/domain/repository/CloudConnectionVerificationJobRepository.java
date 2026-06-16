package com.example.dvely.cloudconnection.domain.repository;

import com.example.dvely.cloudconnection.domain.model.CloudConnectionVerificationJob;
import java.util.List;
import java.util.Optional;

public interface CloudConnectionVerificationJobRepository {

    CloudConnectionVerificationJob save(CloudConnectionVerificationJob job);

    Optional<CloudConnectionVerificationJob> findById(String jobId);

    Optional<CloudConnectionVerificationJob> findByIdAndOwnerUserId(String jobId, Long ownerUserId);

    Optional<CloudConnectionVerificationJob> findLatestByCloudConnectionId(Long cloudConnectionId);

    List<String> claimPending(String workerId, int limit);

    void recoverExpiredLeases();
}
