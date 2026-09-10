package com.example.dvely.cloudconnection.infrastructure.persistence.repository;

import com.example.dvely.cloudconnection.infrastructure.persistence.entity.CloudConnectionVerificationJobEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataCloudConnectionVerificationJobRepository
        extends JpaRepository<CloudConnectionVerificationJobEntity, String> {

    Optional<CloudConnectionVerificationJobEntity> findByIdAndOwnerUserId(String id, Long ownerUserId);

    Optional<CloudConnectionVerificationJobEntity> findFirstByCloudConnectionIdOrderByCreatedAtDesc(
            Long cloudConnectionId
    );

    @Query("""
            select job.id
            from CloudConnectionVerificationJobEntity job
            where job.status = :pendingStatus
            order by job.createdAt asc
            """)
    List<String> findPendingIds(@Param("pendingStatus") String pendingStatus, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CloudConnectionVerificationJobEntity job
               set job.status = :runningStatus,
                   job.connectionStatus = :verifyingStatus,
                   job.message = :message,
                   job.attempt = job.attempt + 1,
                   job.leaseOwner = :workerId,
                   job.leaseUntil = :leaseUntil,
                   job.startedAt = :startedAt,
                   job.updatedAt = :startedAt
             where job.id = :jobId
               and job.status = :pendingStatus
            """)
    int claim(
            @Param("jobId") String jobId,
            @Param("workerId") String workerId,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus,
            @Param("verifyingStatus") String verifyingStatus,
            @Param("message") String message
    );

    // #340 5-2: claim 된 job 을 실행기에 넘기지 못했을 때 PENDING 으로 되돌린다. claim 이 올린
    // attempt 를 그대로 되돌린다 — 실행기 포화는 이 job 의 실패가 아니라 배압이다.
    // connection_status 를 VERIFYING 으로 두는 것은 retryAfterExpiredLease 와 같다: 사용자가 검증을
    // 요청해 둔 상태 그대로이고, 곧 다음 폴링이 다시 집는다. WHERE 는 claim 과 같은 조건부 UPDATE
    // 모양이라 이 claim 을 실제로 쥔 워커만 되돌린다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CloudConnectionVerificationJobEntity job
               set job.status = :pendingStatus,
                   job.attempt = job.attempt - 1,
                   job.message = :message,
                   job.startedAt = null,
                   job.leaseOwner = null,
                   job.leaseUntil = null,
                   job.updatedAt = :now
             where job.id = :jobId
               and job.status = :runningStatus
               and job.leaseOwner = :workerId
            """)
    int releaseClaim(
            @Param("jobId") String jobId,
            @Param("workerId") String workerId,
            @Param("now") LocalDateTime now,
            @Param("runningStatus") String runningStatus,
            @Param("pendingStatus") String pendingStatus,
            @Param("message") String message
    );

    List<CloudConnectionVerificationJobEntity> findByStatusAndLeaseUntilBefore(
            String status,
            LocalDateTime leaseUntil
    );
}
