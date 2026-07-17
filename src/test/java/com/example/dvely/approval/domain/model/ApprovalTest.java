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
}
