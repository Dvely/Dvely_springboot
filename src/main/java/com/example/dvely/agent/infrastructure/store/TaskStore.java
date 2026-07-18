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
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class TaskStore {

    private static final List<String> RUNNABLE_STATUSES = List.of(
            TaskStatus.QUEUED.name(),
            TaskStatus.RETRY_WAIT.name()
    );
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

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

    @Transactional
    public void recoverExpiredLeases() {
        List<AgentRunEntity> expired = runRepository.findByStatusAndLeaseUntilBefore(
                TaskStatus.RUNNING.name(),
                LocalDateTime.now()
        );
        for (AgentRunEntity run : expired) {
            if (run.getAttempt() >= run.getMaxAttempts()) {
                run.markFailed(
                        "중단된 실행의 최대 복구 횟수에 도달했습니다.",
                        null,
                        "task 로그를 확인한 뒤 새 요청으로 다시 실행해주세요."
                );
                appendEvent(
                        run.getTaskId(),
                        "RECOVERY_EXHAUSTED",
                        TaskStatus.FAILED,
                        "중단된 실행의 최대 복구 횟수에 도달했습니다."
                );
                continue;
            }
            run.recoverExpiredLease();
            appendEvent(
                    run.getTaskId(),
                    "LEASE_RECOVERED",
                    TaskStatus.RETRY_WAIT,
                    "중단된 실행을 감지해 다시 대기열에 넣었습니다."
            );
        }
    }

    @Transactional
    public void renewWorkerLeases(String workerId) {
        runRepository.renewWorkerLeases(
                workerId,
                LocalDateTime.now().plus(LEASE_DURATION),
                TaskStatus.RUNNING.name()
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
     */
    @Transactional
    public void markWaitingResultApproval(String taskId, String message) {
        AgentRunEntity run = requireRun(taskId);
        run.waitForResultApproval();
        appendEvent(taskId, "WAITING_RESULT_APPROVAL", TaskStatus.WAITING_RESULT_APPROVAL, message);
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

    @Transactional
    public boolean cancel(String taskId, Long ownerUserId) {
        AgentRunEntity run = runRepository.findByTaskIdAndOwnerUserId(taskId, ownerUserId)
                .orElse(null);
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
