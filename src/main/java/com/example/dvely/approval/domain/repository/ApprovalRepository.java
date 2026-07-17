package com.example.dvely.approval.domain.repository;

import com.example.dvely.approval.domain.model.Approval;
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

    List<Approval> findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(Long projectId, Long ownerUserId);

    List<Approval> findByTaskIdOrderByIdAsc(String taskId);
}
