package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.approval.domain.value.RejectOption;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 지난 사용자 요청이 어떻게 끝났는지. 계획을 세우는 모델에 넘기는 이력에 붙인다.
 *
 * <p>모델은 지난 요청을 "아직 안 한 일"로 읽고 이번 지시에 다시 취합했다 — 사용자가 결과를 거절하고 되돌린 요청까지
 * 다음 요청에서 이번 작업의 변경으로 되살아났다(2026-10-28 dev). 결과를 함께 주면 무엇을 다시 하면 안 되는지 알 수 있다.</p>
 */
@Component
@RequiredArgsConstructor
public class RequestOutcomeResolver {

    private final TaskStore taskStore;
    private final ApprovalRepository approvalRepository;

    public enum Outcome {
        /** 결과를 거절하고 preview 를 되돌렸다 */
        REVERTED,
        /** 사용자가 거절했다(계획이든 결과든) */
        REJECTED,
        CANCELLED,
        /** 실패해 반영되지 않았다(push 거부 포함) */
        FAILED,
        DONE,
        /** 진행 중이거나 알 수 없다 */
        UNKNOWN
    }

    public Outcome resolve(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Outcome.UNKNOWN;
        }
        // 거절이 먼저다 — 결과를 거절하면 태스크도 CANCELLED 가 되는데, 그보다 "사용자가 거절했다"가 더 정확한 사실이다
        List<Approval> approvals = approvalRepository.findByTaskIdOrderByIdAsc(taskId);
        for (Approval approval : approvals) {
            if (approval.getStatus() == ApprovalStatus.REJECTED && approval.getType() != ApprovalType.REPOSITORY_BINDING) {
                return approval.getType() == ApprovalType.RESULT && approval.getRejectOption() == RejectOption.REVERT
                        ? Outcome.REVERTED : Outcome.REJECTED;
            }
        }
        AgentTask task = taskStore.get(taskId);
        if (task == null) {
            return Outcome.UNKNOWN;
        }
        TaskStatus status = task.status();
        return switch (status) {
            case CANCELLED -> Outcome.CANCELLED;
            case FAILED -> Outcome.FAILED;
            case DONE -> Outcome.DONE;
            default -> Outcome.UNKNOWN;
        };
    }
}
