package com.example.dvely.approval.application.port.out;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.value.ApprovalType;

/**
 * SPI for the approve/reject follow-up of a standalone approval (taskId == null, design D6) —
 * an approval created directly from an API request rather than from an agent plan. Implementors
 * live in whichever domain owns the thing being approved (e.g. project's
 * {@code InfrastructureChangeApprovalHandler} for INFRA_OPERATION).
 * <p>
 * Implementations run <b>inside</b> {@code ApprovalCommandService.approve/reject}'s existing
 * {@code @Transactional} boundary — they are called after the approval's own status has been
 * saved but before the surrounding transaction commits. Throwing from {@link #onApproved} or
 * {@link #onRejected} therefore rolls back the approval status change too, so a failure here
 * never leaves the approval decided but its effect un-applied (or vice versa).
 */
public interface StandaloneApprovalHandler {

    boolean supports(ApprovalType type);

    void onApproved(Approval approval);

    void onRejected(Approval approval);
}
