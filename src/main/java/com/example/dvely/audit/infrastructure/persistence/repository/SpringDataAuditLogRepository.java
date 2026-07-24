package com.example.dvely.audit.infrastructure.persistence.repository;

import com.example.dvely.audit.infrastructure.persistence.entity.AuditLogEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataAuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    // Trailing Pageable supplies only the limit window (PageRequest.of(0, limit) from the
    // adapter) — the ORDER BY itself comes from the derived method name (design §2.1: id desc ==
    // insertion order, matches both project-scoped indexes' trailing column).
    List<AuditLogEntity> findByProjectIdOrderByIdDesc(Long projectId, Pageable pageable);

    List<AuditLogEntity> findByProjectIdAndCategoryOrderByIdDesc(Long projectId, String category, Pageable pageable);

    // Native + LIMIT (design §8): plain JPQL bulk deletes cannot express ORDER BY/LIMIT, but MySQL
    // supports both on a single-table DELETE. Bounding each statement to `batchSize` rows keeps any
    // one sweep's row-lock hold time and undo-log growth small, at the cost of the scheduler having
    // to call this repeatedly (see AuditLogRetentionScheduler).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "delete from audit_logs where created_at < :cutoff order by audit_log_id limit :batchSize",
            nativeQuery = true)
    int deleteBatch(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
