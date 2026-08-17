package com.example.dvely.agent.infrastructure.worker;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 사람의 결정을 기다리다 그대로 방치된 Agent task 를 닫는다.
 *
 * WAITING_APPROVAL 과 WAITING_RESULT_APPROVAL 은 워커가 집을 수 없는 상태라, 승인도 거절도
 * 오지 않으면 스스로 빠져나올 길이 없다. 그래서 결정되지 않은 태스크는 PENDING 승인을 달고
 * 영구히 남는다. dev 에서 20건이 이 상태로 쌓여 있었고 전부 current_step=0 이었다.
 *
 * {@link StuckApprovalSweeper} 와 짝이지만 조건이 정반대라 서로를 건드리지 않는다. 저쪽은
 * "승인이 전부 APPROVED 인데 실행으로 못 넘어간" 태스크를 QUEUED 로 되살리고, 이쪽은 "아무도
 * 결정하지 않은" 태스크를 CANCELLED 로 닫는다. 결정이 일부만 된 태스크는 TTL 을 넘기기 전까지
 * 어느 쪽에도 걸리지 않는다.
 *
 * TTL 은 넉넉해야 한다. 사용자가 승인 카드를 띄워둔 채 하루 자리를 비우는 것은 정상이고, 그걸
 * 취소해버리면 이 스윕이 오히려 기능을 망가뜨린다. 그래서 기본값은 7일이고, 잘못 잡혔을 때
 * 되돌릴 수 있도록 환경변수로 뺀다. 0 이하로 두면 스윕을 끈다.
 */
@Slf4j
@Component
public class AbandonedApprovalSweeper {

    private final TaskStore taskStore;
    private final AgentOrchestrator agentOrchestrator;
    private final Duration ttl;

    public AbandonedApprovalSweeper(
            TaskStore taskStore,
            AgentOrchestrator agentOrchestrator,
            @Value("${qeploy.agent.approval.abandon-ttl:7d}") Duration ttl
    ) {
        this.taskStore = taskStore;
        this.agentOrchestrator = agentOrchestrator;
        this.ttl = ttl;
    }

    @Scheduled(fixedDelayString = "${qeploy.agent.approval.abandon-sweep-interval-ms:3600000}")
    public void sweep() {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        List<String> candidates = taskStore.findAbandonedApprovalTaskIds(ttl);
        for (String taskId : candidates) {
            try {
                agentOrchestrator.abandonStaleApprovalTask(taskId);
            } catch (Exception exception) {
                // 한 건의 실패가 나머지 배치를 멈추지 않게 한다 — StuckApprovalSweeper 와 같은
                // 태스크 단위 격리 원칙이다.
                log.warn("[AbandonedApprovalSweeper] 방치 태스크 정리 실패 — 다음 스윕에서 재시도합니다. taskId={}",
                        taskId, exception);
            }
        }
    }
}
