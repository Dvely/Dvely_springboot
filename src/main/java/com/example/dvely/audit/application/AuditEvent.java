package com.example.dvely.audit.application;

import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;

/**
 * Single call-site-facing parameter object for {@link AuditRecorder#record}/{@link AuditLogWriter}
 * (design §2.3) — a plain record instead of a builder to keep every hook a one-line call while
 * avoiding a 10-argument method signature. {@code detail}/{@code errorSummary} are carried through
 * as-is here; truncation and redaction are applied once, centrally, in
 * {@code com.example.dvely.audit.domain.model.AuditLog#from} (design §5.1/§7) so no call site can
 * forget either step.
 *
 * @param action       what happened — also determines {@code category} (see {@link AuditAction#category()})
 * @param outcome      terminal result of the action
 * @param actorType    who caused it (design ADR-A8)
 * @param actorUserId  nullable — the user this action is attributed to (always set for USER/AGENT;
 *                     set for SYSTEM only when a clear owner exists, e.g. H8's history.ownerUserId)
 * @param projectId    nullable — every hook in this unit sets it; reserved for future account-level events
 * @param resourceType nullable — see design §2.1 DDL comment for the fixed value set
 * @param resourceId   nullable — natural-key string (numeric PK or e.g. a repo full name)
 * @param taskId       nullable — agent task correlation, not a foreign key
 * @param approvalId   nullable — approval correlation, not a foreign key
 * @param detail       nullable — caller-assembled allowlist summary only (design §7 rule 1)
 * @param errorSummary nullable — raw exception message; redaction/truncation happens downstream
 */
public record AuditEvent(
        AuditAction action,
        AuditOutcome outcome,
        AuditActorType actorType,
        Long actorUserId,
        Long projectId,
        String resourceType,
        String resourceId,
        String taskId,
        Long approvalId,
        String detail,
        String errorSummary
) {
}
