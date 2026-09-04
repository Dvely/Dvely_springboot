package com.example.dvely.agent.infrastructure.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class StuckPendingSweeperTest {

    private static final Duration GRACE = Duration.ofMinutes(15);

    @Test
    void sweepsEveryCandidateTaskId() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        StuckPendingSweeper sweeper = new StuckPendingSweeper(taskStore, orchestrator, GRACE);
        when(taskStore.findStalePendingTaskIds(GRACE)).thenReturn(List.of("task-1", "task-2"));

        sweeper.sweep();

        verify(orchestrator).failStalePendingTask("task-1");
        verify(orchestrator).failStalePendingTask("task-2");
    }

    @Test
    void oneCandidateFailingDoesNotAbortTheRestOfTheBatch() {
        // 다른 스윕과 같은 태스크 단위 격리 — 한 건의 예외가 나머지 후보 처리를 막지 않아야 한다.
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        StuckPendingSweeper sweeper = new StuckPendingSweeper(taskStore, orchestrator, GRACE);
        when(taskStore.findStalePendingTaskIds(GRACE)).thenReturn(List.of("task-1", "task-2"));
        doThrow(new IllegalStateException("boom")).when(orchestrator).failStalePendingTask("task-1");

        sweeper.sweep();

        verify(orchestrator).failStalePendingTask("task-1");
        verify(orchestrator).failStalePendingTask("task-2");
    }

    @Test
    void noCandidatesMeansNoOrchestratorCalls() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        StuckPendingSweeper sweeper = new StuckPendingSweeper(taskStore, orchestrator, GRACE);
        when(taskStore.findStalePendingTaskIds(GRACE)).thenReturn(List.of());

        sweeper.sweep();

        verifyNoInteractions(orchestrator);
    }

    @Test
    void graceOfZeroDisablesTheSweepEntirely() {
        // grace 를 0 이하로 두면 스윕을 끈다 — 후보 조회조차 하지 않는다.
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        StuckPendingSweeper sweeper = new StuckPendingSweeper(taskStore, orchestrator, Duration.ZERO);

        sweeper.sweep();

        verifyNoInteractions(taskStore);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void negativeGraceAlsoDisablesTheSweep() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        StuckPendingSweeper sweeper = new StuckPendingSweeper(taskStore, orchestrator, Duration.ofMinutes(-1));

        sweeper.sweep();

        verifyNoInteractions(taskStore);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void findStalePendingIsQueriedWithTheConfiguredGrace() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        Duration customGrace = Duration.ofMinutes(30);
        StuckPendingSweeper sweeper = new StuckPendingSweeper(taskStore, orchestrator, customGrace);
        when(taskStore.findStalePendingTaskIds(any())).thenReturn(List.of());

        sweeper.sweep();

        verify(taskStore).findStalePendingTaskIds(customGrace);
    }
}
