package com.example.dvely.agent.infrastructure.worker;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * ADR-Y4 (#55) — JVM-local registry of task IDs this worker instance has actually committed to
 * executing. Membership here is what {@code AgentRunWorker#renewLeases} scopes its heartbeat
 * renewal to: a task this worker claimed but the executor rejected (pool saturation) must never
 * keep its lease alive through heartbeat, or {@code recoverExpiredLeases} could never reclaim it —
 * the exact D2/G1 "RUNNING zombie, self-renewing lease" bug this design fixes.
 * <p>
 * Registration MUST happen right before executor submission, in {@code AgentRunWorker}'s dispatch
 * loop — never inside {@link com.example.dvely.agent.application.orchestrator.AgentPlanExecutor
 * #execute} itself. A task that has been successfully submitted to the executor but is still
 * sitting in its internal queue (not yet running on a thread) still needs to be heartbeat-
 * protected: registering only once the executor's own thread starts running {@code execute()}
 * would leave that queued-but-not-yet-started window unregistered, letting its lease expire and
 * {@code recoverExpiredLeases} hand the same work to a second claim while the original queued
 * submission is still going to run it later — a double execution.
 * <p>
 * {@link ConcurrentHashMap#newKeySet()} is used (not a synchronized collection) because the two
 * callers — the scheduled dispatch thread (register/unregister) and the {@code agentExecutor} pool
 * threads (unregister, from {@code AgentPlanExecutor}'s {@code finally}) — never need a
 * consistent multi-operation view of each other, only thread-safe individual add/remove/snapshot.
 */
@Component
public class AgentExecutionRegistry {

    private final Set<String> registeredTaskIds = ConcurrentHashMap.newKeySet();

    public void register(String taskId) {
        registeredTaskIds.add(taskId);
    }

    public void unregister(String taskId) {
        registeredTaskIds.remove(taskId);
    }

    /** Immutable point-in-time snapshot for heartbeat's {@code task_id IN (...)} filter. */
    public Set<String> snapshot() {
        return Set.copyOf(registeredTaskIds);
    }
}
