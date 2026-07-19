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

    // Backs ApprovalRepository#existsByProjectIdAndTypeAndStatus. Reuses the existing
    // idx_approvals_project_id index (V14) — MySQL scans by project_id then filters type/status
    // in-place, which is fine given the small, bounded number of approvals per project; no new
    // index/migration needed for this narrow existence check.
    boolean existsByProjectIdAndTypeAndStatus(Long projectId, String type, String status);

    List<ApprovalEntity> findByTaskIdOrderByIdAsc(String taskId);

    // ADR-Y1 §1 step① — closed interface projection: Spring Data resolves this straight from the
    // two aliased scalar columns below, never materializing an ApprovalEntity, so it cannot poison
    // the persistence context's L1 cache the way an unlocked entity load would (see
    // ApprovalRouting's javadoc).
    interface RoutingView {
        String getTaskId();
        String getType();
    }

    @Query("select a.taskId as taskId, a.type as type from ApprovalEntity a "
            + "where a.id = :approvalId and a.ownerUserId = :ownerUserId")
    Optional<RoutingView> findRoutingInfo(
            @Param("approvalId") Long approvalId, @Param("ownerUserId") Long ownerUserId
    );

    // Backs ApprovalRepository#findByTaskIdOrderByIdAscForUpdate (see its javadoc for why the
    // all-approved check needs a locking, not plain, read).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApprovalEntity a where a.taskId = :taskId order by a.id asc")
    List<ApprovalEntity> findByTaskIdOrderByIdAscForUpdate(@Param("taskId") String taskId);
}
