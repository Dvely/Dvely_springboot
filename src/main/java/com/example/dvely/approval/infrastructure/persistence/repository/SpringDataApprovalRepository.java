package com.example.dvely.approval.infrastructure.persistence.repository;

import com.example.dvely.approval.infrastructure.persistence.entity.ApprovalEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataApprovalRepository extends JpaRepository<ApprovalEntity, Long> {

    Optional<ApprovalEntity> findByIdAndOwnerUserId(Long approvalId, Long ownerUserId);

    // PESSIMISTIC_WRITE issues SELECT ... FOR UPDATE — must only ever be called inside an
    // existing @Transactional (ApprovalCommandService.approve/reject), otherwise Hibernate has
    // no transaction to hold the lock for. A derived-name method can't carry @Lock, hence the
    // explicit @Query here even though it's a plain equality lookup (review F1).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApprovalEntity a where a.id = :approvalId and a.ownerUserId = :ownerUserId")
    Optional<ApprovalEntity> findByIdAndOwnerUserIdForUpdate(
            @Param("approvalId") Long approvalId, @Param("ownerUserId") Long ownerUserId
    );

    List<ApprovalEntity> findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(Long projectId, Long ownerUserId);

    List<ApprovalEntity> findByTaskIdOrderByIdAsc(String taskId);
}
