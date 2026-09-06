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
 * 시작되지 않은 채 방치된 PENDING Agent task 를 닫는다.
 *
 * {@code ChatCommandService#sendMessage} 는 Decision(LLM 호출)을 요청 스레드 밖으로 빼기 위해
 * {@code AgentOrchestrator#createPending} 으로 PENDING 태스크를 먼저 커밋한 뒤, 백그라운드에서
 * 계획을 확정한다. 정상 흐름에서는 그 백그라운드 작업이 태스크를 QUEUED/승인 대기로 확정하거나
 * {@code markDecisionFailed} 로 FAILED 로 닫는다 — 둘 중 하나는 반드시 일어난다.
 *
 * 그런데 그 짧은 창(커밋~확정) 사이에 프로세스가 죽으면(배포 재기동 등) 백그라운드 작업이 사라져
 * 태스크가 계획 없이 PENDING 으로 남는다. 워커는 QUEUED/RETRY_WAIT 만 집고, 다른 두 스윕은
 * 승인 대기 상태만 보므로, 이 PENDING 은 어디에도 걸리지 않고 영구히 남는다 — 이 스윕이 유일한
 * 출구다.
 *
 * grace 는 한 번의 Decision 최대 소요(#238 read timeout 180s × 재시도 예산)보다 넉넉히 커야 한다.
 * 짧게 잡으면 아직 살아서 응답을 기다리는 Decision 의 PENDING 을 잘못 닫는다. 기본 15분은 그
 * 최대치(약 9분)보다 충분히 크다. 0 이하로 두면 스윕을 끈다.
 */
@Slf4j
@Component
public class StuckPendingSweeper {

    private final TaskStore taskStore;
    private final AgentOrchestrator agentOrchestrator;
    private final Duration grace;

    public StuckPendingSweeper(
            TaskStore taskStore,
            AgentOrchestrator agentOrchestrator,
            @Value("${qeploy.agent.pending.stale-grace:15m}") Duration grace
    ) {
        this.taskStore = taskStore;
        this.agentOrchestrator = agentOrchestrator;
        this.grace = grace;
    }

    @Scheduled(fixedDelayString = "${qeploy.agent.pending.stale-sweep-interval-ms:300000}")
    public void sweep() {
        if (grace.isZero() || grace.isNegative()) {
            return;
        }
        List<String> candidates = taskStore.findStalePendingTaskIds(grace);
        for (String taskId : candidates) {
            try {
                agentOrchestrator.failStalePendingTask(taskId);
            } catch (Exception exception) {
                // 한 건의 실패가 나머지 배치를 멈추지 않게 한다 — 다른 스윕과 같은 태스크 단위
                // 격리 원칙이다.
                log.warn("[StuckPendingSweeper] 방치 PENDING 태스크 정리 실패 — 다음 스윕에서 재시도합니다. taskId={}",
                        taskId, exception);
            }
        }
    }
}
