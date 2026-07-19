package com.example.dvely.agent.application.orchestrator;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.InfraOperation;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final TaskStore              taskStore;
    private final ProjectRepository      projectRepository;
    private final ConversationRepository conversationRepository;
    private final ProjectApprovalPolicyRepository policyRepository;
    private final ApprovalRepository approvalRepository;
    private final AgentMessageService agentMessageService;

    @Transactional
    public AgentSubmission submit(AgentPlan plan, Long userId, Long conversationId) {
        Long projectId = resolveProjectId(userId, plan.projectId(), conversationId);
        AgentPlan normalizedPlan = new AgentPlan(plan.steps(), plan.reasoning(), plan.aiProvider(), projectId);
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        taskStore.save(new AgentTask(
                taskId,
                userId,
                normalizedPlan.projectId(),
                conversationId,
                TaskStatus.PENDING,
                null,
                null,
                null,
                null,
                Instant.now()
        ));
        taskStore.savePlan(taskId, normalizedPlan);

        List<Approval> approvals = createRequiredApprovals(normalizedPlan, taskId, userId, conversationId);
        if (approvals.isEmpty()) {
            agentMessageService.appendAssistant(conversationId, "승인 정책에 따라 작업을 시작합니다.");
            taskStore.enqueue(taskId);
            return new AgentSubmission(taskId, TaskStatus.QUEUED, List.of());
        }

        String approvalSummary = buildApprovalMessage(approvals);
        taskStore.markWaitingApproval(taskId, approvalSummary);
        agentMessageService.appendAssistant(conversationId, approvalSummary);
        return new AgentSubmission(
                taskId,
                TaskStatus.WAITING_APPROVAL,
                approvals.stream().map(Approval::getId).toList()
        );
    }

    /**
     * ADR-Y1 §1 step⑥-plan guard (#55): callers only ever reach this once every plan approval for
     * the task has already been observed APPROVED under the task lock (ApprovalCommandService's
     * locking read) or via the sweep's equivalent re-verification — so at this point the task's
     * status is authoritative, not a race: no concurrent decision-maker for this taskId can be
     * mutating it (the task lock the caller already holds serializes them out). Any status other
     * than WAITING_APPROVAL or FAILED reaching here is therefore a caller/state-machine bug, not a
     * timing window, and surfaces as 409 rather than silently transitioning an unexpected state.
     */
    public void executeApproved(String taskId) {
        AgentTask task = taskStore.get(taskId);
        AgentPlan plan = taskStore.getPlan(taskId);
        if (task == null || plan == null) {
            throw new IllegalStateException("실행할 Agent task를 찾을 수 없습니다. taskId=" + taskId);
        }
        if (task.status() != TaskStatus.WAITING_APPROVAL && task.status() != TaskStatus.FAILED) {
            throw new IllegalStateException(
                    "승인 완료 처리를 진행할 수 없는 상태입니다. taskId=" + taskId + " status=" + task.status());
        }
        if (task.status() == TaskStatus.FAILED) {
            if (!taskStore.retry(taskId, task.ownerUserId())) {
                throw new IllegalStateException("재시도할 수 없는 Agent task입니다. taskId=" + taskId);
            }
            return;
        }
        taskStore.enqueue(taskId);
    }

    /**
     * Resumes a task past its RESULT approval (design D3/§4.2) — deliberately a separate method
     * from {@link #executeApproved}, not a reuse of it: {@code executeApproved} has a
     * FAILED-then-retry branch (build-failure recovery) that has nothing to do with this "resume
     * a still-in-progress task's remaining steps" case, and its "every approval decided" chat
     * message would be wrong here (RESULT approvals never join the plan-approval
     * {@code allApproved} vote — see {@code ApprovalCommandService}'s RESULT branch). Throws
     * (-> 409, E-RA-03) instead of silently no-op'ing so a racing duplicate approve — or an
     * approve arriving after the task was independently cancelled — surfaces as a conflict rather
     * than pretending to have resumed something that no longer needs resuming.
     */
    public void resumeAfterResult(String taskId) {
        if (!taskStore.resumeAfterResultApproval(taskId)) {
            throw new IllegalStateException(
                    "결과 승인 대기 상태가 아닌 Agent task입니다. taskId=" + taskId);
        }
    }

    /**
     * Review follow-up (BLOCKING-3): the "may this RESULT approval still proceed" half of
     * resuming a task past its result-approval gate, split out from {@link #resumeAfterResult} so
     * {@code ApprovalCommandService} can call this <em>before</em> {@code
     * ResultApprovalService#reflect()}'s irreversible GitHub merge, and only call {@link
     * #resumeAfterResult} itself after that merge has already succeeded. See {@code
     * TaskStore#requireWaitingResultApproval} for the row-locking contract that makes the later
     * {@link #resumeAfterResult} call safe to assume will not fail on this same guard.
     */
    public void verifyResumableAfterResult(String taskId) {
        taskStore.requireWaitingResultApproval(taskId);
    }

    /**
     * Y6-a (#55): now shares {@link #cancelTaskCascade} with user cancel — a rejected task's
     * sibling PENDING approvals are cancelled too, closing audit G6's asymmetry (previously reject
     * left them PENDING while cancel already cancelled them). See {@link #cancelTaskCascade}'s
     * javadoc for the invariant this establishes.
     */
    public void reject(String taskId, Long ownerUserId) {
        if (!cancelTaskCascade(taskId, ownerUserId)) {
            throw new IllegalStateException("거절할 Agent task를 찾을 수 없습니다. taskId=" + taskId);
        }
    }

    @Transactional
    public boolean cancel(String taskId, Long ownerUserId) {
        return cancelTaskCascade(taskId, ownerUserId);
    }

    /**
     * Y6-a (#55): shared by user cancel ({@code DELETE /tasks/{id}}) and approval reject — both
     * must leave the task CANCELLED with every sibling PENDING approval also CANCELLED. This
     * establishes the invariant "task 터미널 ⇒ PENDING 승인 없음": a sibling approve arriving after
     * either path now 409s on {@code Approval#decide}'s own PENDING guard instead of leaving a
     * decided-but-orphaned APPROVED record on an already-terminal task (closes audit G6), and it is
     * what lets ADR-Y2's sweep condition ("every approval APPROVED") stay a simple predicate with no
     * extra "and no terminal-task leftovers" special case.
     * <p>
     * Review follow-up (BLOCKING-1, y-hardening-review.md): the sibling scan below MUST be a
     * locking read ({@code findByTaskIdOrderByIdAscForUpdate}), not the plain {@code
     * findByTaskIdOrderByIdAsc} this used before — "the caller's transaction already holds the
     * task lock by the time this runs" is true but insufficient. Design §1's REPEATABLE READ rule
     * ("a transaction's first non-locking SELECT fixes the snapshot every later non-locking SELECT
     * in that transaction reuses") bites here specifically because {@code
     * ApprovalCommandService.reject()}'s very first statement is {@code routingFor()} (a plain
     * scalar read, step① of ADR-Y1) — which runs <em>before</em> {@code taskStore.lockTask}. That
     * fixes this transaction's snapshot before the task lock is even acquired, so a later plain
     * read here — even though it runs after the lock is held — can still observe a sibling
     * approval as stale PENDING when it was actually already committed APPROVED by a concurrent
     * approve() that finished and released the task lock in between. Unlike {@code
     * ApprovalEntity}, which carries no {@code @Version}, that stale read would silently overwrite
     * the sibling's real APPROVED decision with CANCELLED — no exception, no log, a genuine data
     * corruption via write-skew, exactly the class of bug ADR-Y1 exists to eliminate. A locking
     * read bypasses the snapshot and always observes the latest committed version, same as
     * approve()'s all-approved check (§1) and {@code recoverStuckApprovedTask}'s re-verification.
     * It never actually contends here either: this transaction is already the sole task-lock
     * holder, so there is no other writer to block on.
     * <p>
     * Approvals are visited in id-ascending order (LO-1, design §2.1).
     */
    private boolean cancelTaskCascade(String taskId, Long ownerUserId) {
        if (!taskStore.cancel(taskId, ownerUserId)) {
            return false;
        }
        approvalRepository.findByTaskIdOrderByIdAscForUpdate(taskId).stream()
                .filter(approval -> approval.getStatus() == ApprovalStatus.PENDING)
                .forEach(approval -> {
                    approval.cancel();
                    approvalRepository.save(approval);
                });
        return true;
    }

    /**
     * ADR-Y2 (#55) — the sweep's actual lock-and-reverify step, invoked per candidate by {@code
     * StuckApprovalSweeper}. Uses the exact same lock order as the plan-approve path above (task
     * row first, then a *locking* read of every approval), so a still-in-flight approve() call for
     * the same task simply makes this call wait for the lock rather than race it; once acquired,
     * the status re-check below means a task the in-flight approve() already resolved is a correct,
     * silent no-op here — never a double transition.
     * <p>
     * Under ADR-Y1's lock hierarchy this stuck state can no longer newly occur, so any real
     * candidate reaching the transition below is itself a regression signal — logged at WARN
     * accordingly (design §4.3 "발동 자체가 이상 신호").
     */
    @Transactional
    public void recoverStuckApprovedTask(String taskId) {
        AgentTask task = taskStore.lockTask(taskId);
        if (task.status() != TaskStatus.WAITING_APPROVAL) {
            return; // a racing approve/reject/cancel already resolved it — no-op (design §4.3)
        }
        List<Approval> approvals = approvalRepository.findByTaskIdOrderByIdAscForUpdate(taskId);
        boolean allApproved = !approvals.isEmpty() && approvals.stream()
                .allMatch(approval -> approval.getStatus() == ApprovalStatus.APPROVED);
        if (!allApproved) {
            return; // normal in-progress wait — not every approval has been decided yet
        }
        taskStore.recoverStuckApproval(taskId);
        log.warn("[AgentOrchestrator] 고착된 승인 완료 태스크를 스윕으로 복구했습니다 — ADR-Y1 이후 이 로그의 발생은 "
                + "회귀 신호입니다. taskId={}", taskId);
        agentMessageService.appendAssistant(task.conversationId(), "지연된 승인 처리를 복구해 작업을 시작합니다.");
    }

    /**
     * Issue #64 fix: brought into ADR-Y1's task-bound-decision discipline (same lock hierarchy as
     * approve/reject/cancel/RESULT/sweep — see {@link TaskStore#lockTask} javadoc) so its
     * "no PENDING approval blocks retry" check and the retry action itself are now one atomic unit
     * under a single task-row mutex hold, instead of the pre-#64 TOCTOU: a non-locking check
     * ({@code findPendingApprovalId}) followed by {@code taskStore.retry(...)} in what was
     * effectively a second, unsynchronized decision — a PENDING approval could be decided (or the
     * task's status could change) in the gap between the two with nothing serializing against it.
     * <p>
     * Lock order is task row first (identical to every other task-bound decision), so this
     * introduces no new deadlock class — it actually removes one: pre-#64, two concurrent direct
     * {@code retry()} calls on the same task (e.g. a duplicate click) each ran {@code
     * taskStore.retry(...)}'s non-locking read-then-write with nothing serializing them, which
     * MySQL could only resolve by killing one transaction with a real deadlock
     * ({@code CannotAcquireLockException}) — a raw 500-class failure instead of a clean refusal.
     * See {@code OrchestrationConcurrencyIntegrationTest
     * #concurrentDirectRetryCallsOnTheSameFailedTaskNeverDeadlockAndOnlyOneEverSucceeds} for the
     * Red/Green proof (reliably deadlocks on the pre-#64 code, passes deterministically after this
     * fix) and {@code
     * #concurrentRetryAndApproveOfItsBlockingPendingApprovalNeverDoubleRetriesTheSameFailedTask}
     * for the retry-vs-approve interleaving (exactly one of {retry(), the FAILED-branch retry
     * inside approve()'s {@link #executeApproved}} ever performs the transition; the other
     * correctly refuses — never both, never neither). Both rely on the same fact: a concurrent
     * approve()/retry() on this taskId already takes the identical {@code taskStore.lockTask}
     * first, so every pair of task-bound decisions now simply serializes on this row instead of
     * racing.
     * <p>
     * Uses a *locking* read ({@code findByTaskIdOrderByIdAscForUpdate}), not the non-locking
     * {@link #findPendingApprovalId} the read-only {@code GET /tasks/{id}} "retryable" hint still
     * uses — under MySQL REPEATABLE READ a plain SELECT here could still observe a pre-lock
     * snapshot even after waiting for and acquiring the task lock (design §1, the same reasoning
     * {@code ApprovalCommandService.approve()}'s all-approved check and {@code cancelTaskCascade}'s
     * sibling scan already rely on). {@code taskStore.retry(...)} below then runs inside this same
     * transaction (default {@code REQUIRED} propagation — deliberately not {@code REQUIRES_NEW},
     * so it joins rather than opens a second transaction that would not benefit from the lock this
     * method just acquired) and performs its own fresh, non-locking status/attempt check — safe
     * here specifically because it is that non-locking read, not the locking approvals scan above,
     * that becomes this transaction's first non-locking read: it is taken while this transaction is
     * already the row's sole lock holder, so no concurrent writer can be racing it.
     */
    @Transactional
    public boolean retry(String taskId, Long ownerUserId) {
        // ADR-Y1 lock order: task row first, before any approvals read (mirrors
        // approve/reject/cancel/sweep exactly — see class-level lock-hierarchy note above).
        taskStore.lockTask(taskId);
        boolean pendingApprovalBlocksRetry = approvalRepository.findByTaskIdOrderByIdAscForUpdate(taskId).stream()
                .anyMatch(approval -> approval.getStatus() == ApprovalStatus.PENDING);
        return !pendingApprovalBlocksRetry && taskStore.retry(taskId, ownerUserId);
    }

    /**
     * Read-only "is this task still blocked on an approval" hint (#57, QA report §5.6/H1/M3),
     * surfaced by {@code GET /tasks/{id}} to fold into {@code retryable} and expose
     * {@code pendingApprovalId} — before #57, {@code AgentTaskFailure.retryable()} computed
     * eligibility from {@code attempt < maxAttempts} alone, with no knowledge of a still-PENDING
     * approval such as {@code BuildFailureRecoveryService}'s "자동 수정 및 재build" CHANGE approval,
     * so a FAILED task could read {@code retryable:true} and still 409 on {@code /retry}.
     * <p>
     * Post-#64: {@link #retry} no longer calls this method — it needs a *locking* re-verification
     * under the task-row lock it now holds (see its javadoc), while this method is intentionally
     * non-locking (a plain {@code findByTaskIdOrderByIdAsc}) since it backs a read-only GET and
     * taking no lock is exactly right for a display hint that never blocks on contention. Both
     * still share the identical judgment — "any PENDING approval, any {@link
     * com.example.dvely.approval.domain.value.ApprovalType}, blocks retry" — so the two can never
     * silently drift apart on *what* counts as blocking, only on *how fresh* the read is
     * (non-locking snapshot here vs. always-latest-committed inside {@link #retry}'s transaction).
     * A narrow window where this hint reads stale (e.g. {@code retryable:true} for a moment after
     * a PENDING approval was just created) is an acceptable trade for a GET endpoint — the action
     * gate ({@link #retry}) is what actually enforces correctness, and it re-reads under lock
     * regardless of what this hint last said.
     * <p>
     * Returns the oldest (id-ascending, LO-1) PENDING approval's id when more than one exists, or
     * {@code null} when the task has no outstanding approval at all.
     */
    public Long findPendingApprovalId(String taskId) {
        return approvalRepository.findByTaskIdOrderByIdAsc(taskId).stream()
                .filter(approval -> approval.getStatus() == ApprovalStatus.PENDING)
                .map(Approval::getId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Y6-b (#55): collects every required step's summary per {@link ApprovalType} instead of
     * keeping only the first (the old {@code putIfAbsent} silently dropped every later step's
     * summary — a plan with two CODE steps showed only the first one in its single CHANGE
     * approval, §16.7 information loss). Approval cardinality still stays one-per-type — see
     * {@link #mergeSummaries} javadoc for why step-per-approval is deliberately out of scope here.
     */
    private List<Approval> createRequiredApprovals(AgentPlan plan,
                                                   String taskId,
                                                   Long userId,
                                                   Long conversationId) {
        ProjectApprovalPolicy policy = resolvePolicy(plan.projectId());
        Map<ApprovalType, List<String>> summariesByType = new LinkedHashMap<>();
        for (AgentStep step : plan.steps()) {
            ApprovalType type = toApprovalType(step);
            if (type == null || !policy.requires(type)) {
                continue;
            }
            summariesByType.computeIfAbsent(type, key -> new ArrayList<>()).add(summarize(step));
        }

        List<Approval> approvals = new ArrayList<>();
        summariesByType.forEach((type, stepSummaries) -> approvals.add(approvalRepository.save(new Approval(
                userId,
                plan.projectId(),
                conversationId,
                taskId,
                type,
                mergeSummaries(stepSummaries)
        ))));
        return approvals;
    }

    /**
     * Merges every step's one-line summary for a single approval type into one numbered block
     * ("1) …\n2) …") — a single-step type stays exactly as it was before (no "1) " prefix added)
     * so this is behavior-preserving for the common case. Approval cardinality deliberately stays
     * one-per-type (design Y6-b): a step-per-approval model would ripple into the plan-approve
     * all-approved vote, build-failure recovery's dedupe lookup ({@code
     * BuildFailureRecoveryService#findPendingRecoveryApproval}), and the FE approval UI — out of
     * scope for a concurrency-hardening unit. Truncated to 480 (not the {@code approvals.summary
     * VARCHAR(500)} column's full 500) to always leave headroom for the "..." marker without ever
     * exceeding the column.
     */
    private String mergeSummaries(List<String> stepSummaries) {
        if (stepSummaries.size() == 1) {
            return truncateSummary(stepSummaries.get(0));
        }
        StringBuilder merged = new StringBuilder();
        for (int i = 0; i < stepSummaries.size(); i++) {
            if (i > 0) {
                merged.append('\n');
            }
            merged.append(i + 1).append(") ").append(stepSummaries.get(i));
        }
        return truncateSummary(merged.toString());
    }

    private String truncateSummary(String text) {
        int maxLength = 480;
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 3) + "...";
    }

    private ProjectApprovalPolicy resolvePolicy(Long projectId) {
        if (projectId == null) {
            return new ProjectApprovalPolicy(null);
        }
        return policyRepository.findByProjectId(projectId)
                .orElseGet(() -> new ProjectApprovalPolicy(projectId));
    }

    /**
     * Takes the whole {@link AgentStep} (design D7), not just its {@link AgentType} — INFRA_OPERATE
     * is the first type whose approval requirement depends on a step parameter (the "operation"
     * catalog entry) rather than being fixed per type. An unparseable/missing operation and a
     * read-only or unsupported operation both resolve to {@code null} (no approval): the former
     * because there is nothing concrete to approve yet (InfraOpsAgentService will respond with
     * guidance instead), the latter per the catalog's own {@code approvalRequired()} contract.
     */
    private ApprovalType toApprovalType(AgentStep step) {
        return switch (step.agentType()) {
            case CODE -> ApprovalType.CHANGE;
            case DEPLOY -> ApprovalType.DEPLOYMENT;
            case DOMAIN_BIND -> ApprovalType.DOMAIN_BINDING;
            case INFRA_OPERATE -> InfraOperation.parse(step.parameters().get("operation"))
                    .filter(InfraOperation::approvalRequired)
                    .map(op -> ApprovalType.INFRA_OPERATION)
                    .orElse(null);
            case CHAT -> null;
        };
    }

    /**
     * Truncated instruction text used as the Approval row's one-line summary. For INFRA_OPERATE
     * steps that carry a detected impact (BI-176/177), the catalog's marker(s) — e.g.
     * {@code "[서비스 영향] "} — are prepended so the impact is visible at the single point the user
     * sees before approving (design D7/§16.7), rather than only being known internally to the
     * approval-gating decision above. Truncation stays at 200 chars on the instruction itself (the
     * marker is added on top of that, well within the summary column's 500-char limit).
     */
    private String summarize(AgentStep step) {
        String instruction = step.parameters().getOrDefault("instruction", "").trim();
        if (instruction.isEmpty()) {
            instruction = step.agentType().name() + " 작업";
        }
        String truncated = instruction.length() <= 200 ? instruction : instruction.substring(0, 197) + "...";
        String markers = step.agentType() == AgentType.INFRA_OPERATE
                ? InfraOperation.parse(step.parameters().get("operation")).map(InfraOperation::impactMarkers).orElse("")
                : "";
        return markers + truncated;
    }

    private String buildApprovalMessage(List<Approval> approvals) {
        StringBuilder message = new StringBuilder("작업 계획을 만들었습니다. 승인 후 실행합니다.");
        for (Approval approval : approvals) {
            message.append("\n- [")
                    .append(approval.getId())
                    .append("] ")
                    .append(approval.getType())
                    .append(": ")
                    .append(approval.getSummary());
        }
        return message.toString();
    }

    public Long resolveProjectId(Long userId, Long requestedProjectId, Long conversationId) {
        Long projectId = requestedProjectId;
        if (conversationId != null) {
            Conversation conversation = conversationRepository
                    .findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                    .orElseThrow(() -> new NotFoundException("대화를 찾을 수 없습니다. conversationId=" + conversationId));
            if (projectId != null && !projectId.equals(conversation.getProjectId())) {
                throw new IllegalArgumentException("대화와 프로젝트가 일치하지 않습니다.");
            }
            projectId = conversation.getProjectId();
        }

        if (projectId != null && projectRepository
                .findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .isEmpty()) {
            throw new NotFoundException("프로젝트를 찾을 수 없습니다. projectId=" + projectId);
        }

        return projectId;
    }
}
