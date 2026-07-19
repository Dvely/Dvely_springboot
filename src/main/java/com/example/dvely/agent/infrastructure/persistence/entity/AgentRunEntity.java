package com.example.dvely.agent.infrastructure.persistence.entity;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "agent_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentRunEntity {

    @Id
    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "chat_session_id")
    private Long conversationId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "plan_json", columnDefinition = "LONGTEXT")
    private String planJson;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "preview_url", length = 1000)
    private String previewUrl;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "question", columnDefinition = "TEXT")
    private String question;

    @Column(name = "input_value", columnDefinition = "TEXT")
    private String inputValue;

    @Column(name = "failure_log", columnDefinition = "TEXT")
    private String failureLog;

    @Column(name = "suggested_fix", columnDefinition = "TEXT")
    private String suggestedFix;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private AgentRunEntity(AgentTask task) {
        taskId = task.taskId();
        ownerUserId = task.ownerUserId();
        projectId = task.projectId();
        conversationId = task.conversationId();
        status = task.status().name();
        previewUrl = task.previewUrl();
        summary = task.summary();
        error = task.error();
        question = task.question();
        currentStep = 0;
        attempt = 0;
        maxAttempts = 3;
        nextRunAt = LocalDateTime.now();
    }

    public static AgentRunEntity from(AgentTask task) {
        return new AgentRunEntity(task);
    }

    public AgentTask toTask() {
        Instant createdInstant = createdAt == null
                ? Instant.now()
                : createdAt.atZone(ZoneId.systemDefault()).toInstant();
        return new AgentTask(
                taskId,
                ownerUserId,
                projectId,
                conversationId,
                TaskStatus.valueOf(status),
                previewUrl,
                summary,
                error,
                question,
                createdInstant
        );
    }

    public void savePlan(String planJson) {
        this.planJson = planJson;
    }

    public void waitForApproval(String summary) {
        transition(TaskStatus.WAITING_APPROVAL);
        this.summary = summary;
    }

    public void enqueue(boolean retry) {
        transition(retry ? TaskStatus.RETRY_WAIT : TaskStatus.QUEUED);
        if (retry) {
            attempt++;
        }
        nextRunAt = LocalDateTime.now();
        leaseOwner = null;
        leaseUntil = null;
        question = null;
        inputValue = null;
        error = null;
    }

    public void completeStep(int nextStep) {
        currentStep = nextStep;
    }

    public void updateProgress(String previewUrl, String summary) {
        if (previewUrl != null && !previewUrl.isBlank()) {
            this.previewUrl = previewUrl;
        }
        if (summary != null && !summary.isBlank()) {
            this.summary = summary;
        }
    }

    public void markDone(String previewUrl, String summary) {
        transition(TaskStatus.DONE);
        this.previewUrl = previewUrl;
        this.summary = summary;
        clearExecutionState();
    }

    public void markFailed(String error, String failureLog, String suggestedFix) {
        transition(TaskStatus.FAILED);
        this.error = error;
        this.failureLog = failureLog;
        this.suggestedFix = suggestedFix;
        clearExecutionState();
    }

    public void waitForInput(String question) {
        transition(TaskStatus.WAITING_INPUT);
        this.question = question;
        leaseOwner = null;
        leaseUntil = null;
    }

    /**
     * Track Z (#56) D3: transitions into the result-approval gate right after the plan's last
     * CODE step completes. Lease is released the same way {@link #waitForInput} releases it —
     * this row is no longer RUNNING, so there is nothing left for a worker to renew, and holding
     * a stale lease would only be confusing to read later. Unlike {@link #waitForApproval}, this
     * intentionally does NOT overwrite {@code summary}: by this point summary already holds the
     * actual CODE-step result text (set via {@link #updateProgress} moments earlier), which is
     * what {@code GET /tasks/{id}} should keep showing while the task waits — the gate's own
     * "review and approve" message is delivered separately as a chat message, not through this
     * field.
     * <p>
     * Review follow-up (BLOCKING-2): no-ops on a terminal task (mirrors {@link #cancel}'s own
     * {@code isTerminal()} check) instead of unconditionally overwriting {@code status} the way
     * {@link #transition} normally does. This closes the gap the review found: a task cancelled
     * while the gate's slow preview push was still running used to come back to life as
     * WAITING_RESULT_APPROVAL the instant the push finished, because this method never checked
     * what state it was overwriting. {@code TaskStore.markWaitingResultApproval} already checks
     * for CANCELLED before calling this — this guard is the belt-and-suspenders entity-level
     * invariant so "terminal states are never overwritten" holds even for a future caller that
     * invokes this entity method directly.
     */
    public void waitForResultApproval() {
        if (isTerminal()) {
            return;
        }
        transition(TaskStatus.WAITING_RESULT_APPROVAL);
        leaseOwner = null;
        leaseUntil = null;
    }

    /**
     * Resumes a task past its result-approval gate (design D3) — the WAITING_RESULT_APPROVAL
     * mirror of {@link #supplyInput}'s WAITING_INPUT -> QUEUED resume. Returns {@code false}
     * instead of transitioning when the row is not currently WAITING_RESULT_APPROVAL (E-RA-03):
     * callers use this as a compare-and-swap guard against a racing duplicate approve, or against
     * approving a RESULT approval whose task already moved on (e.g. was cancelled) between the
     * approval decision and this call.
     */
    public boolean resumeAfterResultApproval() {
        if (TaskStatus.valueOf(status) != TaskStatus.WAITING_RESULT_APPROVAL) {
            return false;
        }
        enqueue(false);
        return true;
    }

    public void supplyInput(String inputValue) {
        this.inputValue = inputValue;
        transition(TaskStatus.QUEUED);
        nextRunAt = LocalDateTime.now();
        leaseOwner = null;
        leaseUntil = null;
    }

    public String consumeInput() {
        String value = inputValue;
        inputValue = null;
        return value;
    }

    public boolean cancel(Long ownerUserId) {
        if (!this.ownerUserId.equals(ownerUserId) || isTerminal()) {
            return false;
        }
        transition(TaskStatus.CANCELLED);
        clearExecutionState();
        return true;
    }

    // recoverExpiredLease() removed (design ADR-Y5, #55) — expired-lease recovery is now performed
    // by two atomic conditional UPDATEs (SpringDataAgentRunRepository#failExhaustedLease /
    // #recoverLease) instead of this read-then-modify entity method, closing audit G7's
    // multi-instance double-recovery race. See TaskStore#recoverOneExpiredLease.

    public void clearPlan() {
        planJson = null;
    }

    private void transition(TaskStatus taskStatus) {
        status = taskStatus.name();
    }

    private boolean isTerminal() {
        TaskStatus taskStatus = TaskStatus.valueOf(status);
        return taskStatus == TaskStatus.DONE
                || taskStatus == TaskStatus.CANCELLED;
    }

    private void clearExecutionState() {
        question = null;
        inputValue = null;
        nextRunAt = null;
        leaseOwner = null;
        leaseUntil = null;
    }
}
