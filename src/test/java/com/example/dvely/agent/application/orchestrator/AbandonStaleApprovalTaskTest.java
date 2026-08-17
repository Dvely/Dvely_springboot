package com.example.dvely.agent.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 사람의 결정을 기다리다 방치된 태스크를 닫는 경로. WAITING_APPROVAL / WAITING_RESULT_APPROVAL 은
 * 워커가 집을 수 없어 결정이 오지 않으면 스스로 빠져나올 길이 없고, StuckApprovalSweeper 는
 * 조건이 정반대라 이들을 영원히 지나친다.
 */
class AbandonStaleApprovalTaskTest {

    private final TaskStore taskStore = mock(TaskStore.class);
    private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
    private final AgentMessageService messageService = mock(AgentMessageService.class);
    private final AgentOrchestrator orchestrator = new AgentOrchestrator(
            taskStore,
            mock(ProjectRepository.class),
            mock(ConversationRepository.class),
            mock(ProjectApprovalPolicyRepository.class),
            approvalRepository,
            messageService
    );

    @Test
    void cancelsTheTaskAndItsPendingApprovals() {
        givenTask(TaskStatus.WAITING_APPROVAL);
        Approval pending = approval(ApprovalStatus.PENDING);
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of(pending));

        assertThat(orchestrator.abandonStaleApprovalTask("task-1")).isTrue();

        assertThat(pending.getStatus()).isEqualTo(ApprovalStatus.CANCELLED);
        verify(approvalRepository).save(pending);
    }

    @Test
    void appliesToTheResultGateToo() {
        // 이 태스크는 이미 코드를 만들고 프리뷰까지 띄웠지만, 결정이 오지 않으면 프리뷰만 자체
        // TTL 로 만료되고 태스크는 영구히 남는다.
        givenTask(TaskStatus.WAITING_RESULT_APPROVAL);
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of());

        assertThat(orchestrator.abandonStaleApprovalTask("task-1")).isTrue();
    }

    @Test
    void leavesAlreadyDecidedTasksAlone() {
        // 후보 스캔과 잠금 사이에 누군가 승인해 QUEUED 로 넘어갔다면 조용한 no-op 이어야 한다.
        givenTask(TaskStatus.QUEUED);

        assertThat(orchestrator.abandonStaleApprovalTask("task-1")).isFalse();

        verify(taskStore, never()).cancel(anyString(), any());
        verify(messageService, never()).appendAssistant(any(), anyString());
    }

    @Test
    void cancelsAsTheTasksOwnerSoTheSweepCannotTouchSomeoneElsesTask() {
        givenTask(TaskStatus.WAITING_APPROVAL);
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of());

        orchestrator.abandonStaleApprovalTask("task-1");

        verify(taskStore).cancel("task-1", 7L);
    }

    @Test
    void reportsFailureWhenTheCancelDoesNotApply() {
        givenTask(TaskStatus.WAITING_APPROVAL);
        when(taskStore.cancel("task-1", 7L)).thenReturn(false);

        assertThat(orchestrator.abandonStaleApprovalTask("task-1")).isFalse();
        verify(messageService, never()).appendAssistant(any(), anyString());
    }

    /**
     * 사용자가 직접 취소한 경우도 대화에 흔적을 남긴다. 화면에서는 방금 누른 행동이라 자명하지만,
     * 나중에 대화를 다시 열었을 때 "여기서 멈췄다"가 없으면 이력이 읽히지 않는다.
     */
    @Test
    void userCancelLeavesATraceInTheConversation() {
        when(taskStore.getOwned("task-1", 7L)).thenReturn(new AgentTask(
                "task-1", 7L, 11L, 21L, TaskStatus.RUNNING, null, null, null, null, Instant.now()
        ));
        when(taskStore.cancel("task-1", 7L)).thenReturn(true);
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of());

        assertThat(orchestrator.cancel("task-1", 7L)).isTrue();

        verify(messageService).appendAssistant(21L, "작업을 취소했습니다.");
    }

    @Test
    void rejectDoesNotSayCancelled() {
        // 취소와 거절은 같은 cancelTaskCascade 를 쓴다. 안내문을 그 캐스케이드에 넣으면 거절
        // 경로에도 붙어, ApprovalCommandService 가 남기는 거절 문구와 두 벌이 된다.
        when(taskStore.cancel("task-1", 7L)).thenReturn(true);
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of());

        orchestrator.reject("task-1", 7L);

        verify(messageService, never()).appendAssistant(any(), anyString());
    }

    @Test
    void cancelSaysNothingWhenThereWasNothingToCancel() {
        when(taskStore.getOwned("task-1", 7L)).thenReturn(null);
        when(taskStore.cancel("task-1", 7L)).thenReturn(false);

        assertThat(orchestrator.cancel("task-1", 7L)).isFalse();

        verify(messageService, never()).appendAssistant(any(), anyString());
    }

    private void givenTask(TaskStatus status) {
        when(taskStore.lockTask("task-1")).thenReturn(new AgentTask(
                "task-1", 7L, 11L, 21L, status, null, null, null, null, Instant.now()
        ));
        if (status == TaskStatus.WAITING_APPROVAL || status == TaskStatus.WAITING_RESULT_APPROVAL) {
            when(taskStore.cancel(eq("task-1"), eq(7L))).thenReturn(true);
        }
    }

    private Approval approval(ApprovalStatus status) {
        return new Approval(9L, 7L, 11L, 21L, "task-1", ApprovalType.CHANGE, status,
                "[저장소 연결] x", LocalDateTime.now(), null);
    }
}
