package com.example.dvely.approval.infrastructure.persistence.repository;

import com.example.dvely.approval.infrastructure.persistence.entity.ApprovalEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

    // U6(#341) 6-4: 무제한이던 목록에 상한과 커서를 더했다. created_at 이 DATETIME(초) 라 같은 초의
    // 행 순서가 비결정적인데 커서 페이지네이션에서는 그게 행을 건너뛰거나 두 번 주는 버그가 되므로
    // id 를 tiebreaker 로 붙였다. 최신순이라 커서는 "이 id 보다 작은 것"(= 더 오래된 것)이다.
    @Query("""
            select a
            from ApprovalEntity a
            where a.projectId = :projectId
              and a.ownerUserId = :ownerUserId
              and (:after is null or a.id < :after)
            order by a.createdAt desc, a.id desc
            """)
    List<ApprovalEntity> findProjectApprovalsPage(
            @Param("projectId") Long projectId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("after") Long after,
            Pageable pageable
    );

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
