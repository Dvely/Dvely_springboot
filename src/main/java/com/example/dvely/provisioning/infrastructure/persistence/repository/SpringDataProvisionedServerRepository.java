package com.example.dvely.provisioning.infrastructure.persistence.repository;

import com.example.dvely.provisioning.infrastructure.persistence.entity.ProvisionedServerEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProvisionedServerRepository
        extends JpaRepository<ProvisionedServerEntity, Long> {

    Optional<ProvisionedServerEntity> findByApprovalId(Long approvalId);

    List<ProvisionedServerEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<ProvisionedServerEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);
}
