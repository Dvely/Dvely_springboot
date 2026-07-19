package com.example.dvely.agent.infrastructure.worker;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class StuckApprovalSweeperTest {

    @Test
    void sweepsEveryCandidateTaskId() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        StuckApprovalSweeper sweeper = new StuckApprovalSweeper(taskStore, orchestrator);
        when(taskStore.findStuckWaitingApprovalTaskIds()).thenReturn(List.of("task-1", "task-2"));

        sweeper.sweep();

        verify(orchestrator).recoverStuckApprovedTask("task-1");
        verify(orchestrator).recoverStuckApprovedTask("task-2");
    }

    @Test
    void oneCandidateFailingDoesNotAbortTheRestOfTheBatch() {
        // Same per-task isolation principle as ADR-Y3's dispatch loop — a single candidate's
        // unexpected failure must not prevent the sweep from at least attempting every other
        // candidate in this run.
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        StuckApprovalSweeper sweeper = new StuckApprovalSweeper(taskStore, orchestrator);
        when(taskStore.findStuckWaitingApprovalTaskIds()).thenReturn(List.of("task-1", "task-2"));
        doThrow(new IllegalStateException("boom")).when(orchestrator).recoverStuckApprovedTask("task-1");

        sweeper.sweep();

        verify(orchestrator).recoverStuckApprovedTask("task-1");
        verify(orchestrator).recoverStuckApprovedTask("task-2");
    }

    @Test
    void noCandidatesMeansNoOrchestratorCalls() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        StuckApprovalSweeper sweeper = new StuckApprovalSweeper(taskStore, orchestrator);
        when(taskStore.findStuckWaitingApprovalTaskIds()).thenReturn(List.of());

        sweeper.sweep();

        org.mockito.Mockito.verifyNoInteractions(orchestrator);
    }
}
