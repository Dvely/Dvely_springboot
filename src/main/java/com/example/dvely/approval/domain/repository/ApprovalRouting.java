package com.example.dvely.approval.domain.repository;

import com.example.dvely.approval.domain.value.ApprovalType;

/**
 * Scalar routing info for an approval decision (design y-orchestration-hardening ADR-Y1 §1) —
 * deliberately NOT the {@link com.example.dvely.approval.domain.model.Approval} entity itself.
 * {@code approve}/{@code reject} need to know whether an approval is task-bound before they can
 * decide which lock order to take (task row first for task-bound, single-row for standalone), but
 * loading the full entity to find that out would populate Hibernate's L1 cache with an *unlocked*
 * snapshot — a later {@code findByIdAndOwnerUserIdForUpdate} call for the same row would then
 * return that same stale cached instance (same persistence context, same identity) even though the
 * underlying SQL actually took the row lock, silently defeating the lock this whole design depends
 * on. Fetching only these two immutable columns sidesteps the problem entirely: nothing here is
 * ever mutated by a decision, so there is no "stale" version to accidentally observe.
 *
 * @param taskId {@code null} means the approval is standalone (design D6) — routes to a
 *               {@code StandaloneApprovalHandler} instead of the task-bound lock-then-decide flow.
 * @param type   the approval's type, needed by callers to special-case the RESULT branch before
 *               acquiring any lock.
 */
public record ApprovalRouting(String taskId, ApprovalType type) {

    public boolean isStandalone() {
        return taskId == null;
    }
}
