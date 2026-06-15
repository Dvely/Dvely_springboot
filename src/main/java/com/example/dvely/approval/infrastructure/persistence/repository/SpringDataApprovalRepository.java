package com.example.dvely.approval.infrastructure.persistence.repository;

import com.example.dvely.approval.infrastructure.persistence.entity.ApprovalEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataApprovalRepository extends JpaRepository<ApprovalEntity, Long> {

    Optional<ApprovalEntity> findByIdAndOwnerUserId(Long approvalId, Long ownerUserId);

    List<ApprovalEntity> findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(Long projectId, Long ownerUserId);

    List<ApprovalEntity> findByTaskIdOrderByIdAsc(String taskId);
}
