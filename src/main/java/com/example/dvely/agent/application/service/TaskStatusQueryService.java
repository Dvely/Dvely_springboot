package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentTaskFailure;
import com.example.dvely.agent.application.dto.AnsweredClarification;
import com.example.dvely.agent.application.dto.ClarificationRequest;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.agent.infrastructure.store.TaskStore.StatusSnapshot;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /agent/tasks/{taskId}} 의 읽기(#619). FE 가 태스크를 지켜보는 동안 2초마다(1분 뒤 5초) 부른다.
 *
 * <p>예전에는 컨트롤러가 트랜잭션 없이(OSIV off) 다섯 번 따로 읽었다 — 같은 run 행을 엔티티로 네 번,
 * approvals 를 한 번. 읽을 때마다 트랜잭션을 열고 닫는 의례(SET·COMMIT)가 붙어 요청 하나가 약 25문장이었고,
 * 행마다 응답에 쓰지 않는 {@code plan_json} 까지 실려 왔다. 지금은 읽기 전용 트랜잭션 하나에서 run 행
 * 프로젝션 한 번과 approvals 한 번만 읽는다.</p>
 */
@Service
@RequiredArgsConstructor
public class TaskStatusQueryService {

    private final TaskStore taskStore;
    private final AgentOrchestrator agentOrchestrator;

    @Transactional(readOnly = true)
    public Optional<TaskStatusResult> find(String taskId, Long ownerUserId) {
        StatusSnapshot snapshot = taskStore.getStatusSnapshot(taskId, ownerUserId);
        if (snapshot == null) {
            return Optional.empty();
        }
        // #57: pendingApprovalId 는 retry() 가 실제로 막는 조건과 같은 함수로 구한다 — 읽기 쪽 표시가 /retry 의
        // 판정과 어긋나지 않게. 같은 트랜잭션 안에서 부르므로 run 행과 같은 시점을 본다.
        Long pendingApprovalId = agentOrchestrator.findPendingApprovalId(taskId);
        AgentTaskFailure failure = snapshot.failure();
        boolean retryable = failure != null
                && snapshot.status() == TaskStatus.FAILED
                && failure.retryable()
                && pendingApprovalId == null;
        return Optional.of(new TaskStatusResult(
                snapshot.taskId(),
                snapshot.status(),
                snapshot.previewUrl(),
                snapshot.summary(),
                snapshot.error(),
                snapshot.question(),
                failure,
                retryable,
                pendingApprovalId,
                snapshot.clarification(),
                snapshot.answeredClarification()));
    }

    public record TaskStatusResult(
            String taskId,
            TaskStatus status,
            String previewUrl,
            String summary,
            String error,
            String question,
            AgentTaskFailure failure,
            boolean retryable,
            Long pendingApprovalId,
            ClarificationRequest clarification,
            AnsweredClarification answeredClarification
    ) {}
}
