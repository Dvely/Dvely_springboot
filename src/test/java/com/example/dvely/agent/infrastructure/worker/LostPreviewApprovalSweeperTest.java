package com.example.dvely.agent.infrastructure.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #380 회귀망. 승인할 대상이 사라졌는데 카드만 남던 것을 닫는다. */
class LostPreviewApprovalSweeperTest {

    private final TaskStore taskStore = mock(TaskStore.class);
    private final PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final PreviewProperties properties = new PreviewProperties();

    private LostPreviewApprovalSweeper sweeper() {
        return new LostPreviewApprovalSweeper(taskStore, previewSessionService, orchestrator, properties);
    }

    @Test
    @DisplayName("프리뷰가 회수된 태스크만 닫는다 — 살아 있으면 건드리지 않는다")
    void closesOnlyTheOnesWhosePreviewIsGone() {
        when(taskStore.findResultApprovalWaitingTaskIds()).thenReturn(List.of("alive", "gone"));
        when(previewSessionService.findByTaskId("alive")).thenReturn(Optional.of(mock(PreviewSessionInfo.class)));
        when(previewSessionService.findByTaskId("gone")).thenReturn(Optional.empty());

        sweeper().sweep();

        verify(orchestrator).abandonApprovalTaskWithLostPreview("gone", properties.getApprovalHold());
        // 살아 있는 세션을 닫으면 사용자가 결정하려던 작업물을 스윕이 지우는 것이 된다.
        verify(orchestrator, never()).abandonApprovalTaskWithLostPreview("alive", properties.getApprovalHold());
    }

    @Test
    @DisplayName("사유에 쓸 hold 값은 설정에서 온다 — 문구와 설정이 어긋나지 않게")
    void passesTheConfiguredHoldSoTheReasonMatchesConfiguration() {
        properties.setApprovalHold(Duration.ofHours(2));
        when(taskStore.findResultApprovalWaitingTaskIds()).thenReturn(List.of("gone"));
        when(previewSessionService.findByTaskId("gone")).thenReturn(Optional.empty());

        sweeper().sweep();

        verify(orchestrator).abandonApprovalTaskWithLostPreview("gone", Duration.ofHours(2));
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지를 마저 본다")
    void oneCandidateFailingDoesNotAbortTheRestOfTheBatch() {
        when(taskStore.findResultApprovalWaitingTaskIds()).thenReturn(List.of("task-1", "task-2"));
        when(previewSessionService.findByTaskId(anyString())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("boom"))
                .when(orchestrator).abandonApprovalTaskWithLostPreview("task-1", properties.getApprovalHold());

        sweeper().sweep();

        verify(orchestrator).abandonApprovalTaskWithLostPreview("task-2", properties.getApprovalHold());
    }

    @Test
    @DisplayName("후보가 없으면 아무 일도 하지 않는다")
    void doesNothingWithoutCandidates() {
        when(taskStore.findResultApprovalWaitingTaskIds()).thenReturn(List.of());

        sweeper().sweep();

        verify(orchestrator, never()).abandonApprovalTaskWithLostPreview(anyString(), any());
    }
}
