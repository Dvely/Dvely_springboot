package com.example.dvely.provisioning.infrastructure.persistence.repository;

import com.example.dvely.provisioning.infrastructure.persistence.entity.ProvisionedServerEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataProvisionedServerRepository
        extends JpaRepository<ProvisionedServerEntity, Long> {

    Optional<ProvisionedServerEntity> findByApprovalId(Long approvalId);

    List<ProvisionedServerEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<ProvisionedServerEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("update ProvisionedServerEntity e set e.status = 'BUILDING', e.updatedAt = :now"
            + " where e.id = :id and e.status = 'QUEUED'")
    int claimForBuild(@Param("id") Long id, @Param("now") LocalDateTime now);
}
