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

    List<CloudConnectionVerificationJobEntity> findByStatusAndLeaseUntilBefore(
            String status,
            LocalDateTime leaseUntil
    );
}
