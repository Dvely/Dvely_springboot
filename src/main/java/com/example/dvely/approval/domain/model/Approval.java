package com.example.dvely.approval.domain.model;

import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import java.time.LocalDateTime;
import java.util.Objects;

public class Approval {

    private final Long id;
    private final Long ownerUserId;
    private final Long projectId;
    private final Long conversationId;
    private final String taskId;
    private final ApprovalType type;
    private ApprovalStatus status;
    private final String summary;
    private final LocalDateTime createdAt;
    private LocalDateTime decidedAt;

    public Approval(Long ownerUserId,
                    Long projectId,
                    Long conversationId,
                    String taskId,
                    ApprovalType type,
                    String summary) {
        this(
                null,
                ownerUserId,
                projectId,
                conversationId,
                taskId,
                type,
                ApprovalStatus.PENDING,
                summary,
                null,
                null
        );
    }

    public Approval(Long id,
                    Long ownerUserId,
                    Long projectId,
                    Long conversationId,
                    String taskId,
                    ApprovalType type,
                    ApprovalStatus status,
                    String summary,
                    LocalDateTime createdAt,
                    LocalDateTime decidedAt) {
        this.id = id;
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        this.projectId = projectId;
        this.conversationId = conversationId;
        // Standalone approvals (design D6) have no owning agent task, so taskId is now allowed
        // to be null — but if a caller does pass a non-null value it must still be real text,
        // same as before. Every existing agent-task call site already passes a non-null taskId,
        // so this loosening is behavior-preserving for them.
        this.taskId = taskId == null ? null : requireText(taskId, "taskId");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.summary = normalizeSummary(summary);
        this.createdAt = createdAt;
        this.decidedAt = decidedAt;
    }

    /**
     * Creates a PENDING approval that is not tied to any agent task (design D6) — e.g. an
     * infrastructure configuration change requested directly through the API. {@code taskId}
     * and {@code conversationId} are both null: there is no task to execute on approval and no
     * chat conversation to post a follow-up assistant message to. {@link #isStandalone()}
     * distinguishes these rows so {@code ApprovalCommandService} can dispatch to a
     * {@code StandaloneApprovalHandler} instead of the agent-task approve/reject path.
     */
    public static Approval standalone(Long ownerUserId, Long projectId, ApprovalType type, String summary) {
        return new Approval(null, ownerUserId, projectId, null, null, type, ApprovalStatus.PENDING, summary, null, null);
    }

    public boolean isStandalone() {
        return taskId == null;
    }

    public void approve() {
        decide(ApprovalStatus.APPROVED);
    }

    public void reject() {
        decide(ApprovalStatus.REJECTED);
    }

    public void cancel() {
        decide(ApprovalStatus.CANCELLED);
    }

    private void decide(ApprovalStatus decision) {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException("이미 처리된 승인입니다. approvalId=" + id);
        }
        status = decision;
        decidedAt = LocalDateTime.now();
    }

    /**
     * approvals.summary 컬럼은 VARCHAR(500)이다. 승인창에 한 줄로 띄우는 라벨이라 그 이상이 필요
     * 없고, 요약 전문과 diff 는 게이트가 발동하기 전에 ChangeService 가 project_changes
     * (summary TEXT, diff_text MEDIUMTEXT)에 저장하므로 GET /changes/{id} 로 그대로 볼 수 있다.
     *
     * 길이를 넘긴 채 저장하면 insert 가 Data truncation 으로 실패하고, 그 예외는 게이트를 호출한
     * AgentPlanExecutor 의 catch-all 까지 올라가 태스크를 FAILED 로 끝낸다. 사용자는 승인창 대신
     * 작업 실패를 보게 된다. 실제로 ResultApprovalGate 가 CODE 요약 전문을 그대로 넘기고 있어
     * dev 로그에 이 예외가 20회 쌓여 있었다(2026-08-15 실측).
     *
     * 호출부마다 자르는 걸 기억하는 대신 여기서 한 번 막는다. 라벨이 조금 잘리는 것보다 작업이
     * 통째로 실패하는 쪽이 훨씬 나쁘다. 다만 이건 어디까지나 안전망이고, 호출부는 애초에 한 줄
     * 라벨을 넘기는 것이 맞다.
     */
    static final int MAX_SUMMARY_LENGTH = 500;

    private static String normalizeSummary(String summary) {
        String text = requireText(summary, "summary");
        if (text.length() <= MAX_SUMMARY_LENGTH) {
            return text;
        }
        // MySQL 의 VARCHAR(n) 은 바이트가 아니라 문자 수를 센다. 말줄임표까지 포함해 정확히
        // MAX_SUMMARY_LENGTH 문자가 되도록 자른다.
        return text.substring(0, MAX_SUMMARY_LENGTH - 1) + "…";
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public Long getProjectId() { return projectId; }
    public Long getConversationId() { return conversationId; }
    public String getTaskId() { return taskId; }
    public ApprovalType getType() { return type; }
    public ApprovalStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
}
