package com.example.dvely.agent.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Track Z (#56) review follow-up (BLOCKING-2): entity-level tests for {@link
 * AgentRunEntity#waitForResultApproval()}'s terminal-state guard. {@code TaskStoreTest} already
 * covers the CANCELLED guard at the {@code TaskStore} layer; these tests exercise the entity
 * directly so the invariant ("a terminal task's status is never overwritten") is proven to hold
 * even for a future caller that invokes this entity method without going through
 * {@code TaskStore}.
 */
class AgentRunEntityTest {

    @Test
    void waitForResultApprovalDoesNotOverwriteACancelledTask() {
        AgentRunEntity run = AgentRunEntity.from(task(TaskStatus.RUNNING));
        assertThat(run.cancel(1L)).isTrue();

        run.waitForResultApproval();

        assertThat(run.getStatus()).isEqualTo(TaskStatus.CANCELLED.name());
    }

    @Test
    void waitForResultApprovalDoesNotOverwriteADoneTask() {
        AgentRunEntity run = AgentRunEntity.from(task(TaskStatus.RUNNING));
        run.markDone("preview", "summary");

        run.waitForResultApproval();

        assertThat(run.getStatus()).isEqualTo(TaskStatus.DONE.name());
    }

    @Test
    void waitForResultApprovalTransitionsANonTerminalTask() {
        AgentRunEntity run = AgentRunEntity.from(task(TaskStatus.RUNNING));

        run.waitForResultApproval();

        assertThat(run.getStatus()).isEqualTo(TaskStatus.WAITING_RESULT_APPROVAL.name());
        assertThat(run.getLeaseOwner()).isNull();
    }

    private AgentTask task(TaskStatus status) {
        return new AgentTask("task-1", 1L, 11L, 21L, status, null, null, null, null, Instant.now());
    }
}
