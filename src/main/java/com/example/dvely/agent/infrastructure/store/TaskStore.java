package com.example.dvely.agent.infrastructure.store;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskEvent;
import com.example.dvely.agent.application.dto.AgentTaskFailure;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dvely.agent.infrastructure.persistence.entity.AgentRunEventEntity;
import com.example.dvely.agent.infrastructure.persistence.repository.SpringDataAgentRunEventRepository;
import com.example.dvely.agent.infrastructure.persistence.repository.SpringDataAgentRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class TaskStore {

    private static final List<String> RUNNABLE_STATUSES = List.of(
            TaskStatus.QUEUED.name(),
            TaskStatus.RETRY_WAIT.name()
    );
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    // ADR-Y2: grace period before a WAITING_APPROVAL task becomes a sweep candidate — correctness
    // comes entirely from the task-row lock taken inside recoverStuckApprovedTask (a task whose
    // approve() is genuinely still in flight simply loses that lock race and the sweep no-ops), so
    // this grace period is purely to avoid pointless lock waits against a task an approve() call is
    // actively finishing up on. Not a correctness-affecting number.
    private static final Duration STUCK_APPROVAL_GRACE = Duration.ofSeconds(30);

    private final SpringDataAgentRunRepository runRepository;
    private final SpringDataAgentRunEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void save(AgentTask task) {
        runRepository.save(AgentRunEntity.from(task));
        appendEvent(task.taskId(), "CREATED", task.status(), "Agent task가 생성되었습니다.");
    }

    @Transactional(readOnly = true)
    public AgentTask getOwned(String taskId, Long ownerUserId) {
        return runRepository.findByTaskIdAndOwnerUserId(taskId, ownerUserId)
                .map(AgentRunEntity::toTask)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public AgentTask get(String taskId) {
        return runRepository.findById(taskId)
                .map(AgentRunEntity::toTask)
                .orElse(null);
    }

    @Transactional
    public void savePlan(String taskId, AgentPlan plan) {
        AgentRunEntity run = requireRun(taskId);
        run.savePlan(writePlan(plan));
    }

    @Transactional(readOnly = true)
    public AgentPlan getPlan(String taskId) {
        return runRepository.findById(taskId)
                .map(AgentRunEntity::getPlanJson)
                .filter(json -> !json.isBlank())
                .map(this::readPlan)
                .orElse(null);
    }

    @Transactional
    public void removePlan(String taskId) {
        requireRun(taskId).clearPlan();
    }

    @Transactional
    public void markWaitingApproval(String taskId, String summary) {
        AgentRunEntity run = requireRun(taskId);
        run.waitForApproval(summary);
        appendEvent(taskId, "WAITING_APPROVAL", TaskStatus.WAITING_APPROVAL, summary);
    }

    @Transactional
    public void enqueue(String taskId) {
        AgentRunEntity run = requireRun(taskId);
        run.enqueue(false);
        appendEvent(taskId, "QUEUED", TaskStatus.QUEUED, "Agent task 실행을 대기합니다.");
    }

    @Transactional
    public boolean retry(String taskId, Long ownerUserId) {
        AgentRunEntity run = runRepository.findByTaskIdAndOwnerUserId(taskId, ownerUserId)
                .orElse(null);
        if (run == null
                || TaskStatus.valueOf(run.getStatus()) != TaskStatus.FAILED
                || run.getAttempt() >= run.getMaxAttempts()) {
            return false;
        }
        run.enqueue(true);
        appendEvent(taskId, "RETRY_QUEUED", TaskStatus.RETRY_WAIT, "수정안을 적용해 작업을 다시 실행합니다.");
        return true;
    }

    @Transactional
    public List<String> claimRunnableTasks(String workerId, int limit) {
        List<String> candidates = runRepository.findRunnableTaskIds(
                RUNNABLE_STATUSES,
                LocalDateTime.now(),
                PageRequest.of(0, limit)
        );
        LocalDateTime leaseUntil = LocalDateTime.now().plus(LEASE_DURATION);
        return candidates.stream()
                .filter(taskId -> runRepository.claim(
                        taskId,
                        workerId,
                        leaseUntil,
                        TaskStatus.RUNNING.name(),
                        RUNNABLE_STATUSES
                ) == 1)
                .peek(taskId -> appendEvent(
                        taskId,
                        "STARTED",
                        TaskStatus.RUNNING,
                        "Agent worker가 task 실행을 시작했습니다."
                ))
                .toList();
    }

    /**
     * ADR-Y5: rewritten from read-then-modify to per-task conditional UPDATEs (the same
     * "affected-row-count decides whether we get to act" shape as {@link #claimRunnableTasks}'s
     * {@code claim}, F9's model). This is what makes concurrent recovery attempts against the same
     * expired row safe — two instances (or an instance racing itself across two scheduler ticks)
     * both reading the same stale candidate can each attempt their conditional UPDATE, but only the
     * one that actually still finds {@code status='RUNNING'} at UPDATE time succeeds; the loser's
     * WHERE clause matches zero rows and it silently no-ops instead of double-incrementing
     * {@code attempt} or appending a duplicate event (closes audit G7). Candidate IDs are read
     * unlocked and in taskId-ascending order (LO-2) — the ordering mostly documents intent for a
     * future multi-row batch; today at most one row is realistically contended at a time.
     */
    @Transactional
    public void recoverExpiredLeases() {
        List<String> candidates = runRepository.findExpiredLeaseTaskIds(
                TaskStatus.RUNNING.name(),
                LocalDateTime.now()
        );
        for (String taskId : candidates) {
            recoverOneExpiredLease(taskId);
        }
    }

    private void recoverOneExpiredLease(String taskId) {
        LocalDateTime now = LocalDateTime.now();
        int exhausted = runRepository.failExhaustedLease(
                taskId,
                TaskStatus.RUNNING.name(),
                now,
                TaskStatus.FAILED.name(),
                "중단된 실행의 최대 복구 횟수에 도달했습니다.",
                "task 로그를 확인한 뒤 새 요청으로 다시 실행해주세요."
        );
        if (exhausted == 1) {
            appendEvent(
                    taskId,
                    "RECOVERY_EXHAUSTED",
                    TaskStatus.FAILED,
                    "중단된 실행의 최대 복구 횟수에 도달했습니다."
            );
            return;
        }
        int recovered = runRepository.recoverLease(
                taskId,
                TaskStatus.RUNNING.name(),
                now,
                TaskStatus.RETRY_WAIT.name()
        );
        if (recovered == 1) {
            appendEvent(
                    taskId,
                    "LEASE_RECOVERED",
                    TaskStatus.RETRY_WAIT,
                    "중단된 실행을 감지해 다시 대기열에 넣었습니다."
            );
        }
        // If neither UPDATE affected a row, some other actor (a racing recovery attempt, or a
        // heartbeat that renewed the lease a moment ago) already resolved this row between the
        // candidate read above and now — a correct, silent no-op.
    }

    /**
     * ADR-Y3: atomically hands a claimed-but-rejected task back to QUEUED without touching
     * {@code attempt} — executor pool saturation is backpressure caused by system load, not a
     * failed run, so it must not spend the task's retry budget (maxAttempts=3, design's explicit
     * deviation from "RETRY_WAIT" — see the ADR for why QUEUED is correct here). The WHERE clause
     * mirrors {@code claim}'s own conditional-UPDATE pattern (task_id + status + lease_owner), so
     * this can never race a concurrent {@link #recoverExpiredLeases} attempt at the same row into a
     * double transition: whichever UPDATE commits first wins, the other's WHERE clause matches
     * zero rows.
     *
     * @return true iff this call actually performed the transition (and therefore appended the
     *         {@code DISPATCH_REJECTED} event) — false means some other actor already moved the
     *         row off RUNNING first (e.g. a lease-expiry recovery beat this call to it).
     */
    @Transactional
    public boolean releaseClaim(String taskId, String workerId, long backoffMillis) {
        int updated = runRepository.releaseClaim(
                taskId,
                workerId,
                LocalDateTime.now().plus(Duration.ofMillis(backoffMillis)),
                TaskStatus.RUNNING.name(),
                TaskStatus.QUEUED.name()
        );
        if (updated == 1) {
            appendEvent(
                    taskId,
                    "DISPATCH_REJECTED",
                    TaskStatus.QUEUED,
                    "실행기가 포화 상태라 작업을 재대기열로 반환했습니다."
            );
        }
        return updated == 1;
    }

    /**
     * ADR-Y1's task-row mutex — the single first lock every task-bound decision (approve/reject
     * (plan and RESULT alike)/cancel/sweep) must acquire before touching any {@code approvals} row
     * (lock hierarchy {@code agent_runs -> approvals}, design §2). {@code MANDATORY} propagation is
     * a deliberate contract trap: this method is only meaningful inside an existing transaction —
     * the row lock is released the instant the surrounding transaction ends, so calling this
     * without one would silently open-and-immediately-close a transaction around a lock the caller
     * no longer holds by its very next statement. Failing loudly here (an unambiguous
     * {@code IllegalTransactionStateException}) turns that class of bug into an immediate,
     * unmissable failure instead of an intermittent race discovered in production.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentTask lockTask(String taskId) {
        return runRepository.findByTaskIdForUpdate(taskId)
                .map(AgentRunEntity::toTask)
                .orElseThrow(() -> new IllegalStateException("Agent task를 찾을 수 없습니다. taskId=" + taskId));
    }

    /**
     * ADR-Y2 sweep candidates: WAITING_APPROVAL tasks that have sat unchanged past the grace
     * period. Unlocked scalar read — real re-verification happens under the task lock in
     * {@code AgentOrchestrator#recoverStuckApprovedTask}, so a false positive here (e.g. an
     * approve() that is still genuinely in flight) only costs that call a lock wait, never a wrong
     * transition.
     */
    @Transactional(readOnly = true)
    public List<String> findStuckWaitingApprovalTaskIds() {
        return runRepository.findStuckWaitingApprovalTaskIds(
                TaskStatus.WAITING_APPROVAL.name(),
                LocalDateTime.now().minus(STUCK_APPROVAL_GRACE)
        );
    }

    /**
     * ADR-Y2's actual state transition for a sweep-recovered task — mirrors {@link #enqueue}
     * (WAITING_APPROVAL -&gt; QUEUED) but appends a distinctly-named audit event instead of the
     * generic "QUEUED" one, so this recovery path is distinguishable from an ordinary approve on
     * the {@code agent_run_events} timeline (and, per the ADR, its very occurrence is itself a
     * regression signal worth alarming on).
     */
    @Transactional
    public void recoverStuckApproval(String taskId) {
        AgentRunEntity run = requireRun(taskId);
        run.enqueue(false);
        appendEvent(
                taskId,
                "APPROVAL_SWEEP_RECOVERED",
                TaskStatus.QUEUED,
                "지연된 승인 처리를 복구해 작업을 시작합니다."
        );
    }

    /**
     * ADR-Y4: renewal is scoped to exactly the task IDs the caller's {@code
     * AgentExecutionRegistry} can currently vouch for — an empty collection skips the query
     * entirely rather than issuing a pointless {@code task_id IN ()}. Unconditionally renewing
     * "every RUNNING row owned by workerId" (the pre-#55 behavior) was precisely the D2/G1 bug:
     * a task the executor rejected still looked RUNNING in the DB with no thread behind it, and
     * this call used to keep its lease alive forever regardless.
     */
    @Transactional
    public void renewWorkerLeases(String workerId, Collection<String> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        runRepository.renewWorkerLeases(
                workerId,
                LocalDateTime.now().plus(LEASE_DURATION),
                TaskStatus.RUNNING.name(),
                taskIds
        );
    }

    @Transactional
    public void markStepCompleted(String taskId, int nextStep) {
        requireRun(taskId).completeStep(nextStep);
    }

    @Transactional
    public void updateProgress(String taskId, String previewUrl, String summary) {
        requireRun(taskId).updateProgress(previewUrl, summary);
    }

    @Transactional(readOnly = true)
    public int getCurrentStep(String taskId) {
        return requireRun(taskId).getCurrentStep();
    }

    @Transactional
    public void markDone(String taskId, String previewUrl, String summary) {
        AgentRunEntity run = requireRun(taskId);
        if (TaskStatus.valueOf(run.getStatus()) == TaskStatus.CANCELLED) {
            return;
        }
        run.markDone(previewUrl, summary);
        appendEvent(taskId, "COMPLETED", TaskStatus.DONE, summary);
    }

    @Transactional
    public void markFailed(String taskId, String error) {
        markFailed(taskId, error, null, null);
    }

    @Transactional
    public void markFailed(String taskId, String error, String failureLog, String suggestedFix) {
        AgentRunEntity run = requireRun(taskId);
        if (TaskStatus.valueOf(run.getStatus()) == TaskStatus.CANCELLED) {
            return;
        }
        run.markFailed(error, failureLog, suggestedFix);
        appendEvent(taskId, "FAILED", TaskStatus.FAILED, error);
    }

    /**
     * Track Z (#56) §5.2 ordering contract: callers must invoke this only after the CODE step's
     * diff has already been recorded ({@code ChangeService.record}) and the preview branch has
     * already been pushed to GitHub — this method is the state-transition step of the gate
     * sequence, not a place to trigger those side effects. {@code message} is logged on the
     * {@code agent_run_events} audit trail (mirroring every other {@code markX} method here); it
     * intentionally does not touch the entity's {@code summary} (see
     * {@link AgentRunEntity#waitForResultApproval()} javadoc for why).
     * <p>
     * Review follow-up (BLOCKING-2): guarded with the same {@code if (CANCELLED) return;} pattern
     * already used by {@link #markDone}, {@link #markFailed}, and {@link #markWaitingInput} — this
     * method was the one gap in that convention. Without it, a task cancelled (e.g. via
     * {@code DELETE /tasks/{id}}) while the gate's slow preview-branch push was still in flight
     * would get silently revived from CANCELLED back into WAITING_RESULT_APPROVAL the moment the
     * gate's push finally completed, because {@link AgentRunEntity#waitForResultApproval()} used
     * to transition unconditionally. {@link AgentRunEntity#waitForResultApproval()} now also
     * guards against this directly (defense in depth for any future caller that bypasses
     * {@code TaskStore}), so this check is technically redundant with the entity's own guard —
     * kept anyway so the early {@code return} here also skips appending a misleading
     * "WAITING_RESULT_APPROVAL" audit event for a transition that never actually happened.
     */
    @Transactional
    public void markWaitingResultApproval(String taskId, String message) {
        AgentRunEntity run = requireRun(taskId);
        if (TaskStatus.valueOf(run.getStatus()) == TaskStatus.CANCELLED) {
            return;
        }
        run.waitForResultApproval();
        appendEvent(taskId, "WAITING_RESULT_APPROVAL", TaskStatus.WAITING_RESULT_APPROVAL, message);
    }

    /**
     * Review follow-up (BLOCKING-3): locks the task row ({@code PESSIMISTIC_WRITE}, held for the
     * rest of the caller's transaction) and verifies it is still WAITING_RESULT_APPROVAL —
     * without performing the QUEUED transition itself (that remains {@link
     * #resumeAfterResultApproval}'s job). Callers (the RESULT branch of {@code
     * ApprovalCommandService.approve}) must call this <em>before</em> {@code
     * ResultApprovalService#reflect()}'s irreversible GitHub merge, so a precondition failure
     * (e.g. the task raced to CANCELLED via a concurrent {@code DELETE /tasks/{id}}) aborts with
     * zero external side effects. Previously this same check only ran (via {@link
     * #resumeAfterResultApproval}) <em>after</em> {@code reflect()} had already merged — a failure
     * there rolled back the DB but could not undo the GitHub merge that had already happened.
     * <p>
     * The lock acquired here is what makes the later {@link #resumeAfterResultApproval} call
     * (same transaction, same Hibernate persistence context — no second row fetch) safe to assume
     * will still observe WAITING_RESULT_APPROVAL and therefore succeed.
     *
     * @throws IllegalStateException (-&gt; 409, E-RA-03) if the task is missing or not currently
     *         WAITING_RESULT_APPROVAL.
     */
    @Transactional
    public void requireWaitingResultApproval(String taskId) {
        AgentRunEntity run = runRepository.findByTaskIdForUpdate(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "결과 승인 대기 상태가 아닌 Agent task입니다. taskId=" + taskId));
        if (TaskStatus.valueOf(run.getStatus()) != TaskStatus.WAITING_RESULT_APPROVAL) {
            throw new IllegalStateException(
                    "결과 승인 대기 상태가 아닌 Agent task입니다. taskId=" + taskId);
        }
    }

    /**
     * Requeues a task past its RESULT approval (design D3) — returns {@code false} (no event
     * appended) instead of throwing when the task is not currently WAITING_RESULT_APPROVAL, so
     * {@code AgentOrchestrator.resumeAfterResult} can turn that into a clean 409 (E-RA-03) rather
     * than a raw "task not found"-style error.
     */
    @Transactional
    public boolean resumeAfterResultApproval(String taskId) {
        AgentRunEntity run = requireRun(taskId);
        if (!run.resumeAfterResultApproval()) {
            return false;
        }
        appendEvent(taskId, "RESULT_APPROVED", TaskStatus.QUEUED, "결과 승인이 완료되어 남은 작업을 재개합니다.");
        return true;
    }

    @Transactional
    public void markWaitingInput(String taskId, String question) {
        AgentRunEntity run = requireRun(taskId);
        if (TaskStatus.valueOf(run.getStatus()) == TaskStatus.CANCELLED) {
            return;
        }
        run.waitForInput(question);
        appendEvent(taskId, "WAITING_INPUT", TaskStatus.WAITING_INPUT, question);
    }

    @Transactional
    public boolean supplyInput(String taskId, Long ownerUserId, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        AgentRunEntity run = runRepository.findByTaskIdAndOwnerUserId(taskId, ownerUserId)
                .orElse(null);
        if (run == null || TaskStatus.valueOf(run.getStatus()) != TaskStatus.WAITING_INPUT) {
            return false;
        }
        run.supplyInput(value.trim());
        appendEvent(taskId, "INPUT_RECEIVED", TaskStatus.QUEUED, "사용자 입력을 받아 task를 다시 대기열에 넣었습니다.");
        return true;
    }

    @Transactional
    public Optional<String> consumeInput(String taskId) {
        AgentRunEntity run = requireRun(taskId);
        return Optional.ofNullable(run.consumeInput())
                .filter(value -> !value.isBlank());
    }

    /**
     * ADR-Y1 §2.2: upgraded from an unlocked {@code findByTaskIdAndOwnerUserId} read to the same
     * locking {@code findByTaskIdForUpdate} query {@link #lockTask} uses — this IS the cancel
     * path's task-row-lock acquisition (LO-1's "① task행" for the cancel/reject cascade). Before
     * this change, the task row's effective lock only materialized implicitly at Hibernate's dirty-
     * checking flush (commit time), whose ordering relative to a concurrent approve()'s explicit
     * lock was undefined; an explicit {@code FOR UPDATE} here makes the ordering deterministic
     * instead of dependent on flush timing. Ownership is still checked afterward inside {@link
     * AgentRunEntity#cancel(Long)} — a wrong-owner attempt now briefly takes and releases the row
     * lock with no effect, an acceptable trade for a single shared entry point.
     */
    @Transactional
    public boolean cancel(String taskId, Long ownerUserId) {
        AgentRunEntity run = runRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (run == null || !run.cancel(ownerUserId)) {
            return false;
        }
        appendEvent(taskId, "CANCELLED", TaskStatus.CANCELLED, "사용자가 Agent task를 취소했습니다.");
        return true;
    }

    @Transactional(readOnly = true)
    public boolean isCancelled(String taskId) {
        AgentTask task = get(taskId);
        return task != null && task.status() == TaskStatus.CANCELLED;
    }

    @Transactional(readOnly = true)
    public AgentTaskFailure getFailure(String taskId, Long ownerUserId) {
        return runRepository.findByTaskIdAndOwnerUserId(taskId, ownerUserId)
                .map(run -> new AgentTaskFailure(
                        run.getFailureLog(),
                        run.getSuggestedFix(),
                        run.getAttempt(),
                        run.getMaxAttempts()
                ))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AgentTaskEvent> getEvents(String taskId, Long ownerUserId, Long afterEventId) {
        if (runRepository.findByTaskIdAndOwnerUserId(taskId, ownerUserId).isEmpty()) {
            return List.of();
        }
        return eventRepository
                .findByTaskIdAndIdGreaterThanOrderByIdAsc(taskId, afterEventId == null ? 0L : afterEventId)
                .stream()
                .map(AgentRunEventEntity::toResult)
                .toList();
    }

    private AgentRunEntity requireRun(String taskId) {
        return runRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Agent task를 찾을 수 없습니다. taskId=" + taskId));
    }

    private void appendEvent(String taskId, String type, TaskStatus status, String message) {
        eventRepository.save(new AgentRunEventEntity(taskId, type, status, message));
    }

    private String writePlan(AgentPlan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent plan을 저장하지 못했습니다.", exception);
        }
    }

    private AgentPlan readPlan(String json) {
        try {
            return objectMapper.readValue(json, AgentPlan.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저장된 Agent plan을 읽지 못했습니다.", exception);
        }
    }
}
