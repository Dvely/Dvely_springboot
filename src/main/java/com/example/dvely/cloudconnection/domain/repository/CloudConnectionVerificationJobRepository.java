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

    /**
     * 폴링 한 번이 하는 일 전부 — 만료 리스 회수와 claim 을 <b>한 트랜잭션</b>으로 묶는다(#340 5-1).
     * 따로 부르면 폴링 한 번이 트랜잭션 두 개가 되고, 트랜잭션마다 붙는 {@code SET autocommit} ·
     * {@code COMMIT} 의례가 유휴 DB 비용의 대부분이었다. 회수 UPDATE 가 같은 트랜잭션에서 먼저
     * 반영되므로, 방금 회수된 행을 같은 폴링의 claim 이 곧바로 집는다.
     */
    List<String> recoverAndClaimPending(String workerId, int limit);

    void recoverExpiredLeases();

    /**
     * claim 해 놓고 실행기에 넘기지 못한 job 을 PENDING 으로 되돌린다(#340 5-2).
     *
     * @return 이 호출이 실제로 되돌렸으면 true. false 는 그 사이 다른 주체가 이미 이 행을
     *         RUNNING 밖으로 옮겼다는 뜻이다.
     */
    boolean releaseClaim(String jobId, String workerId);
}
