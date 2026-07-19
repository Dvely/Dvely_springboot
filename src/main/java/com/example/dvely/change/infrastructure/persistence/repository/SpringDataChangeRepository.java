package com.example.dvely.change.infrastructure.persistence.repository;

import com.example.dvely.change.infrastructure.persistence.entity.ChangeEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataChangeRepository extends JpaRepository<ChangeEntity, Long> {

    Optional<ChangeEntity> findByTaskId(String taskId);

    Optional<ChangeEntity> findByIdAndOwnerUserId(Long changeId, Long ownerUserId);

    List<ChangeEntity> findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(Long projectId, Long ownerUserId);

    // Track Z (#56) review follow-up (BLOCKING-1): backs ResultApprovalService#hasResultGateHistory
    // — a project-scoped existence check (any status, not just the current task's own Change row)
    // used to tell whether this project has already had at least one RESULT-gate decision.
    boolean existsByProjectIdAndStatusIn(Long projectId, Collection<String> statuses);
}
