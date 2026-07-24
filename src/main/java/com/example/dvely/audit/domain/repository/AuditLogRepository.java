package com.example.dvely.audit.domain.repository;

import com.example.dvely.audit.domain.model.AuditLog;
import com.example.dvely.audit.domain.value.AuditCategory;
import java.time.LocalDateTime;
import java.util.List;

public interface AuditLogRepository {

    AuditLog save(AuditLog auditLog);

    /** Most recent first (audit_log_id desc — insertion order, design §2.1 ordering note). */
    List<AuditLog> findByProjectIdOrderByIdDesc(Long projectId, int limit);

    /** Same ordering as above, filtered to one category (design §6 query contract). */
    List<AuditLog> findByProjectIdAndCategoryOrderByIdDesc(Long projectId, AuditCategory category, int limit);

    /**
     * Deletes up to {@code batchSize} rows older than {@code cutoff} in one statement, returning
     * the number actually deleted. The retention scheduler (design §8) calls this repeatedly until
     * it returns 0 rather than issuing one unbounded {@code DELETE} — a single huge delete would
     * hold its row locks and grow the undo log for the whole duration, whereas many small deletes
     * each commit (and release locks) quickly.
     */
    int deleteBatch(LocalDateTime cutoff, int batchSize);
}
