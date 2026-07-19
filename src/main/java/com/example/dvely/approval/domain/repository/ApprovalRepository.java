package com.example.dvely.approval.domain.repository;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import java.util.List;
import java.util.Optional;

public interface ApprovalRepository {

    Approval save(Approval approval);

    Optional<Approval> findByIdAndOwnerUserId(Long approvalId, Long ownerUserId);

    /**
     * Same lookup as {@link #findByIdAndOwnerUserId}, but acquires a row-level lock
     * (SELECT ... FOR UPDATE) held for the rest of the caller's transaction. Every approval
     * decision (approve/reject) must go through this method rather than the unlocked lookup —
     * the approval row is the single point of contention for a given approval, so funneling
     * every decide path through one locked query gives a single, easy-to-reason-about lock
     * order and closes the approve-vs-reject race (review F1): the second transaction blocks
     * until the first commits, then re-reads the already-decided row and fails
     * {@code Approval#decide}'s PENDING guard instead of blind-overwriting the outcome.
     */
    Optional<Approval> findByIdAndOwnerUserIdForUpdate(Long approvalId, Long ownerUserId);

    /**
     * ADR-Y1 §1 step①: unlocked scalar lookup (see {@link ApprovalRouting} javadoc for why it must
     * stay scalar) used by {@code ApprovalCommandService} to decide, before taking any lock,
     * whether an approval is task-bound (needs the task-row lock first) or standalone (single-row,
     * order irrelevant).
     */
    Optional<ApprovalRouting> findRoutingInfo(Long approvalId, Long ownerUserId);

    List<Approval> findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(Long projectId, Long ownerUserId);

    List<Approval> findByTaskIdOrderByIdAsc(String taskId);

    /**
     * Same rows as {@link #findByTaskIdOrderByIdAsc}, but a locking read (SELECT ... FOR UPDATE)
     * held for the rest of the caller's transaction — required for the plan-approval "every
     * approval APPROVED?" check (ADR-Y1 §1): under REPEATABLE READ, a plain SELECT can still
     * return a pre-lock snapshot even after the caller waited for and acquired the task row lock,
     * because that snapshot was taken at this transaction's first non-locking read, not at lock
     * acquisition time. A locking read always observes the latest committed version instead. This
     * never actually contends in practice: the task row lock ADR-Y1 requires callers to acquire
     * first already serializes every other decision-maker for the same taskId.
     */
    List<Approval> findByTaskIdOrderByIdAscForUpdate(String taskId);

    /**
     * Track Z (#56) review follow-up (B1 residual, issue #62): true iff this project currently
     * has an approval of the given type/status combination — used by
     * {@code ResultApprovalService#hasResultGateHistory} to detect a PENDING RESULT approval
     * (a decision the gate is *actively* waiting on) in addition to the already-decided
     * REJECTED/MERGED Change rows it already checks. Without this, a direct deploy racing a
     * pending RESULT approval on the same never-yet-released project could merge preview content
     * the user has not decided on yet (narrower cousin of BLOCKING-1: not-yet-decided rather than
     * already-REJECTED content).
     */
    boolean existsByProjectIdAndTypeAndStatus(Long projectId, ApprovalType type, ApprovalStatus status);
}
