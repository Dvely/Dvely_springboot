package com.example.dvely.audit.application;

import com.example.dvely.audit.domain.model.AuditLog;
import com.example.dvely.audit.domain.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sole write path for {@code audit_logs} (design §5.1). Deliberately its own Spring bean, separate
 * from {@link AuditRecorder}: {@code @Transactional} is a proxy-based aspect, so if this method
 * lived on {@code AuditRecorder} and were called via {@code this.write(...)}, the proxy would never
 * intercept the call and {@code REQUIRES_NEW} would silently do nothing (the well-known Spring
 * "self-invocation" trap) — splitting the two into separate beans is what makes the propagation
 * annotation actually take effect.
 */
@Component
@RequiredArgsConstructor
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    /**
     * Always runs in a brand-new transaction, independent of any transaction the caller may be
     * in (design ADR-A2). Contract this method must keep: exactly one INSERT into
     * {@code audit_logs}, and nothing else — no read of, or lock on, any other table. That is what
     * keeps this table a leaf in the lock hierarchy (design §5.2 AL-1): a transaction that only
     * ever inserts into a table with no foreign keys cannot become part of another transaction's
     * lock-wait cycle.
     *
     * <p>{@link AuditLog#from} applies redaction/truncation before this ever reaches the
     * repository, so this method itself does no data massaging — it is intentionally "just" a
     * transaction boundary around a single save.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditEvent event) {
        auditLogRepository.save(AuditLog.from(event));
    }
}
