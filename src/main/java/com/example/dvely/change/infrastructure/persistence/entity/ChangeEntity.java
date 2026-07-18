package com.example.dvely.change.infrastructure.persistence.entity;

import com.example.dvely.change.application.result.ChangeResult;
import com.example.dvely.change.domain.value.ChangeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "project_changes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "change_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "chat_session_id")
    private Long conversationId;

    @Column(name = "task_id", nullable = false, unique = true, length = 64)
    private String taskId;

    @Column(name = "preview_session_id", nullable = false, length = 36)
    private String previewSessionId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "diff_text", columnDefinition = "MEDIUMTEXT")
    private String diffText;

    // V28 (Track Z, #56): the RESULT approval row that decided this change's fate. NULL for
    // legacy/gate-not-fired changes (never went through a RESULT decision).
    @Column(name = "approval_id")
    private Long approvalId;

    // V28: preview -> main PR number. NULL for the idempotent no-op merge path (already merged,
    // or an empty diff — see ResultApprovalService#reflect) since there is no PR to reference.
    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "merge_commit_sha", length = 64)
    private String mergeCommitSha;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ChangeEntity(Long ownerUserId,
                        Long projectId,
                        Long conversationId,
                        String taskId,
                        String previewSessionId,
                        String summary,
                        String diffText) {
        this.ownerUserId = ownerUserId;
        this.projectId = projectId;
        this.conversationId = conversationId;
        this.taskId = taskId;
        this.previewSessionId = previewSessionId;
        this.status = ChangeStatus.PREVIEW_READY.name();
        this.summary = summary;
        this.diffText = diffText;
    }

    public void update(String summary, String diffText) {
        this.summary = summary;
        this.diffText = diffText;
    }

    /**
     * Guarded (design §3.2) to only fire from PREVIEW_READY or MERGED: a REJECTED change must
     * never be flipped to DEPLOYED by a stray/late deployment-success webhook (WebhookEventHandler
     * keys purely by taskId, with no knowledge of the RESULT decision). Not reachable in the
     * normal flow — REJECTED implies the task was CANCELLED, so no DEPLOY step ever runs to
     * trigger this webhook for it — but it is one 3-line guard against a "should never happen"
     * regressing into a silently wrong audit trail, so it stays.
     */
    public void markDeployed() {
        if (!ChangeStatus.PREVIEW_READY.name().equals(status) && !ChangeStatus.MERGED.name().equals(status)) {
            return;
        }
        status = ChangeStatus.DEPLOYED.name();
    }

    /**
     * Track Z (#56) D8: {@code prNumber} is null on the idempotent no-op path (reflect() found no
     * new commits to merge — already merged by a prior partially-failed approve, or the CODE step
     * produced an empty diff) since there is no PR in that case.
     */
    public void markMerged(Long approvalId, Integer prNumber, String mergeCommitSha) {
        this.status = ChangeStatus.MERGED.name();
        this.approvalId = approvalId;
        this.prNumber = prNumber;
        this.mergeCommitSha = mergeCommitSha;
        this.mergedAt = LocalDateTime.now();
    }

    public void markRejected(Long approvalId) {
        this.status = ChangeStatus.REJECTED.name();
        this.approvalId = approvalId;
    }

    public ChangeResult toResult() {
        return new ChangeResult(
                id,
                projectId,
                conversationId,
                taskId,
                previewSessionId,
                status,
                summary,
                approvalId,
                prNumber,
                mergeCommitSha,
                mergedAt,
                createdAt,
                updatedAt
        );
    }
}
