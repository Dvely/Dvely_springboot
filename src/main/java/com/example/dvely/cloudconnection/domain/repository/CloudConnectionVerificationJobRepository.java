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

    /**
     * claim 해 놓고 실행기에 넘기지 못한 job 을 PENDING 으로 되돌린다(#340 5-2).
     *
     * @return 이 호출이 실제로 되돌렸으면 true. false 는 그 사이 다른 주체가 이미 이 행을
     *         RUNNING 밖으로 옮겼다는 뜻이다.
     */
    boolean releaseClaim(String jobId, String workerId);
}
