package com.example.dvely.approval.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import org.junit.jupiter.api.Test;

class ApprovalTest {

    @Test
    void standaloneFactoryCreatesPendingApprovalWithoutTaskIdOrConversationId() {
        Approval approval = Approval.standalone(7L, 11L, ApprovalType.INFRA_OPERATION, "인프라 설정 변경 요청");

        assertThat(approval.getTaskId()).isNull();
        assertThat(approval.getConversationId()).isNull();
        assertThat(approval.getOwnerUserId()).isEqualTo(7L);
        assertThat(approval.getProjectId()).isEqualTo(11L);
        assertThat(approval.getType()).isEqualTo(ApprovalType.INFRA_OPERATION);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approval.getSummary()).isEqualTo("인프라 설정 변경 요청");
    }

    @Test
    void isStandaloneReflectsWhetherTaskIdIsPresent() {
        Approval standalone = Approval.standalone(7L, 11L, ApprovalType.INFRA_OPERATION, "요청");
        Approval taskBound = new Approval(7L, 11L, 21L, "task-1", ApprovalType.CHANGE, "요청");

        assertThat(standalone.isStandalone()).isTrue();
        assertThat(taskBound.isStandalone()).isFalse();
    }

    @Test
    void fullConstructorStillRejectsBlankTaskIdWhenOneIsGiven() {
        assertThatThrownBy(() -> new Approval(
                1L, 7L, 11L, 21L, "   ", ApprovalType.CHANGE, ApprovalStatus.PENDING, "요청", null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullConstructorAllowsNullTaskIdForStandaloneRestore() {
        Approval restored = new Approval(
                5L, 7L, 11L, null, null, ApprovalType.INFRA_OPERATION,
                ApprovalStatus.APPROVED, "요청", null, null
        );

        assertThat(restored.getTaskId()).isNull();
        assertThat(restored.isStandalone()).isTrue();
    }

    // ── summary 길이 안전망 ─────────────────────────────────────────────────────────────

    @Test
    void keepsSummaryAsIsWhenItFitsTheColumn() {
        String summary = "가".repeat(Approval.MAX_SUMMARY_LENGTH);

        Approval approval = new Approval(7L, 11L, 21L, "task-1", ApprovalType.RESULT, summary);

        assertThat(approval.getSummary()).isEqualTo(summary);
    }

    @Test
    void truncatesSummaryThatWouldOverflowTheColumn() {
        // approvals.summary 는 VARCHAR(500). 넘긴 채 저장하면 insert 가 Data truncation 으로
        // 실패하고, 그 예외가 AgentPlanExecutor 까지 올라가 태스크를 FAILED 로 끝낸다.
        String summary = "가".repeat(Approval.MAX_SUMMARY_LENGTH + 200);

        Approval approval = new Approval(7L, 11L, 21L, "task-1", ApprovalType.RESULT, summary);

        assertThat(approval.getSummary()).hasSize(Approval.MAX_SUMMARY_LENGTH);
        assertThat(approval.getSummary()).endsWith("…");
    }

    @Test
    void truncationCountsCharactersNotBytes() {
        // MySQL 의 VARCHAR(n) 은 문자 수를 센다. 한글이 UTF-8 로 3바이트라고 해서 더 짧게 자르면
        // 멀쩡한 라벨이 불필요하게 잘린다.
        String summary = "한".repeat(Approval.MAX_SUMMARY_LENGTH + 1);

        assertThat(new Approval(7L, 11L, 21L, "task-1", ApprovalType.RESULT, summary).getSummary())
                .hasSize(Approval.MAX_SUMMARY_LENGTH);
    }

    @Test
    void stillRejectsBlankSummary() {
        assertThatThrownBy(() -> new Approval(7L, 11L, 21L, "task-1", ApprovalType.RESULT, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
