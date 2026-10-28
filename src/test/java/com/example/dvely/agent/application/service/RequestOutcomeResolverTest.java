package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.service.RequestOutcomeResolver.Outcome;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.approval.domain.value.RejectOption;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestOutcomeResolverTest {

    private final TaskStore taskStore = mock(TaskStore.class);
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final RequestOutcomeResolver resolver = new RequestOutcomeResolver(taskStore, approvals);

    @Test
    void aRejectedResultThatWasRevertedIsReverted() {
        when(approvals.findByTaskIdOrderByIdAsc("t")).thenReturn(List.of(approval(ApprovalType.RESULT, RejectOption.REVERT)));
        task(TaskStatus.CANCELLED);   // 결과 거절은 태스크도 취소한다 — 거절이 더 정확한 사실이다

        assertThat(resolver.resolve("t")).isEqualTo(Outcome.REVERTED);
    }

    @Test
    void aKeptRejectionOrARejectedPlanIsRejected() {
        when(approvals.findByTaskIdOrderByIdAsc("keep")).thenReturn(List.of(approval(ApprovalType.RESULT, RejectOption.KEEP)));
        when(approvals.findByTaskIdOrderByIdAsc("plan")).thenReturn(List.of(approval(ApprovalType.CHANGE, null)));

        assertThat(resolver.resolve("keep")).isEqualTo(Outcome.REJECTED);
        assertThat(resolver.resolve("plan")).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void decliningTheRepositoryBindingIsNotARejectionOfTheWork() {
        // 저장소 연결 거절은 "저장소를 만들지 않는다"는 뜻이고 작업은 그대로 완료된다
        when(approvals.findByTaskIdOrderByIdAsc("t")).thenReturn(List.of(approval(ApprovalType.REPOSITORY_BINDING, null)));
        task(TaskStatus.DONE);

        assertThat(resolver.resolve("t")).isEqualTo(Outcome.DONE);
    }

    @Test
    void taskStatusDecidesTheRest() {
        when(approvals.findByTaskIdOrderByIdAsc("t")).thenReturn(List.of());
        task(TaskStatus.FAILED);
        assertThat(resolver.resolve("t")).isEqualTo(Outcome.FAILED);
        task(TaskStatus.CANCELLED);
        assertThat(resolver.resolve("t")).isEqualTo(Outcome.CANCELLED);
        task(TaskStatus.RUNNING);
        assertThat(resolver.resolve("t")).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void noTaskMeansUnknown() {
        assertThat(resolver.resolve(null)).isEqualTo(Outcome.UNKNOWN);
        when(approvals.findByTaskIdOrderByIdAsc("gone")).thenReturn(List.of());
        assertThat(resolver.resolve("gone")).isEqualTo(Outcome.UNKNOWN);
    }

    private void task(TaskStatus status) {
        AgentTask task = mock(AgentTask.class);
        when(task.status()).thenReturn(status);
        when(taskStore.get("t")).thenReturn(task);
    }

    private static Approval approval(ApprovalType type, RejectOption option) {
        return new Approval(1L, 7L, 11L, 21L, "t", type, ApprovalStatus.REJECTED, "요약",
                LocalDateTime.now(), LocalDateTime.now(), option);
    }
}
