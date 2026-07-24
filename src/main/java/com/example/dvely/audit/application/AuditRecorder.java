package com.example.dvely.audit.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Public entry point every hook (design §4 H1~H14) calls: {@code auditRecorder.record(event)}.
 * This is the entire non-blocking contract of the feature (design ADR-A2) — no exception thrown
 * by the audit write path is ever allowed to propagate out of {@link #record}, because the
 * business operation the hook is attached to (a GitHub push, a deployment state transition, ...)
 * must never fail, retry, or roll back because audit logging had a problem.
 *
 * <p>The try/catch here deliberately wraps the call to {@link AuditLogWriter#write}, not any code
 * inside it: {@code write} is {@code REQUIRES_NEW}, so its transaction commits (or fails to
 * commit) by the time that method call returns to this one — catching around the call is what lets
 * this method also catch a commit-time failure, which same-transaction "piggyback" writes
 * (design ADR-A2's rejected alternative ①) structurally cannot do (a same-tx insert failure poisons
 * the caller's own transaction as rollback-only, so there is nothing left here to "catch and
 * ignore").</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRecorder {

    private final AuditLogWriter writer;

    /** Records one audit event. Never throws — see class javadoc. */
    public void record(AuditEvent event) {
        try {
            writer.write(event);
        } catch (Exception exception) {
            // Fallback trail: if this line itself is ever lost (e.g. log shipping outage), the
            // audit event is genuinely gone — there is no second fallback beneath this one (design
            // §5.3 "유실 창", accepted). The fixed "AUDIT_FALLBACK" prefix exists so operators can
            // alert on this exact string; its appearance at all is the signal, since audit writes
            // are expected to succeed essentially always.
            log.error("AUDIT_FALLBACK action={} outcome={} actorType={} actorUserId={} projectId={} "
                            + "resourceType={} resourceId={} taskId={} approvalId={} detail={}",
                    event.action(), event.outcome(), event.actorType(), event.actorUserId(),
                    event.projectId(), event.resourceType(), event.resourceId(),
                    event.taskId(), event.approvalId(), event.detail(), exception);
        }
    }
}
