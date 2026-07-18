package com.example.dvely.agent.infrastructure.worker;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-Y2 (#55) — periodic defense-in-depth sweep for the D1 write-skew stuck state (a
 * WAITING_APPROVAL task whose every approval already reads APPROVED, because two approvals on the
 * same task were decided concurrently before this hardening unit's ADR-Y1 lock hierarchy existed).
 * ADR-Y1 makes this state structurally impossible to newly enter, so under normal post-#55
 * operation this sweep should find zero candidates every run; its only job is to (a) drain
 * whatever was already stuck before this deploy, once, and (b) act as a permanent safety net
 * against any future regression that reintroduces the race. See {@link
 * AgentOrchestrator#recoverStuckApprovedTask} for the actual lock-and-reverify logic — this class
 * is intentionally a thin polling loop around it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckApprovalSweeper {

    private final TaskStore taskStore;
    private final AgentOrchestrator agentOrchestrator;

    @Scheduled(fixedDelayString = "${qeploy.agent.approval.sweep-interval-ms:60000}")
    public void sweep() {
        List<String> candidates = taskStore.findStuckWaitingApprovalTaskIds();
        for (String taskId : candidates) {
            try {
                agentOrchestrator.recoverStuckApprovedTask(taskId);
            } catch (Exception exception) {
                // One candidate's unexpected failure (e.g. the task row vanishing between the
                // candidate read and the lock attempt — not expected in this codebase today, but
                // not worth crashing the sweep over) must not abort the rest of this batch — same
                // per-task isolation principle as ADR-Y3's dispatch loop.
                log.warn("[StuckApprovalSweeper] 고착 승인 복구 시도 실패 — 다음 스윕에서 재시도합니다. taskId={}",
                        taskId, exception);
            }
        }
    }
}
