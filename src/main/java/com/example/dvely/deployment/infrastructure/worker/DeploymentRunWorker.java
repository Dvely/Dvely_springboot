package com.example.dvely.deployment.infrastructure.worker;

import com.example.dvely.deployment.application.command.DeploymentCommandService;
import com.example.dvely.common.worker.WorkQueue;
import com.example.dvely.common.worker.WorkerPollGate;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import java.lang.management.ManagementFactory;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PENDING 배포를 집어 {@link DeploymentCommandService#executeQueued}(비동기) 로 넘긴다.
 *
 * <p>#340 5-2: 위임을 <b>이력 하나씩 격리</b>한다. 예전에는 루프가 통째로 노출돼 있어,
 * {@code deploymentExecutor} 가 포화된 순간 {@code @Async} 프록시가 던지는
 * {@code TaskRejectedException} 하나가 루프를 끊었다. 그러면 같은 배치에서 <b>이미 claim 된</b>
 * 다음 이력이 IN_PROGRESS 인 채로 남고, 그것을 돌리는 스레드는 어디에도 없으며, 리스 만료(2분)
 * 전까지는 아무도 회수하지 않는다. 사용자 화면에는 그 2분 동안 "배포 중"이 떠 있다.
 * {@code AgentRunWorker} 가 #55(ADR-Y3)에서 이미 고친 것과 같은 버그가 여기에만 남아 있었다.</p>
 */
@Slf4j
@Component
public class DeploymentRunWorker {

    private static final int CLAIM_BATCH_SIZE = 2;

    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final DeploymentCommandService deploymentCommandService;
    private final WorkerPollGate pollGate;
    private final long dispatchRejectBackoffMs;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-deployment";

    public DeploymentRunWorker(DeploymentHistoryRepository deploymentHistoryRepository,
                               DeploymentCommandService deploymentCommandService,
                               WorkerPollGate pollGate,
                               @Value("${qeploy.deployment.worker.dispatch-reject-backoff-ms:5000}")
                               long dispatchRejectBackoffMs) {
        this.deploymentHistoryRepository = deploymentHistoryRepository;
        this.deploymentCommandService = deploymentCommandService;
        this.pollGate = pollGate;
        this.dispatchRejectBackoffMs = dispatchRejectBackoffMs;
    }

    @Scheduled(fixedDelayString = "${qeploy.deployment.worker.poll-interval-ms:1000}")
    public void dispatchPendingDeployments() {
        // #340 5-1: 틱은 1초마다 오지만, 일이 없는 동안에는 DB 를 치지 않는다.
        if (!pollGate.shouldPoll(WorkQueue.DEPLOYMENT_RUN)) {
            return;
        }
        List<Long> historyIds;
        try {
            historyIds = deploymentHistoryRepository.recoverAndClaimPending(workerId, CLAIM_BATCH_SIZE);
        } catch (RuntimeException exception) {
            // DB 가 흔들리는 동안 매초 같은 쿼리를 던져봐야 소용이 없다 — 물러나며 재시도한다.
            pollGate.recordIdle(WorkQueue.DEPLOYMENT_RUN);
            throw exception;
        }
        if (historyIds.isEmpty()) {
            pollGate.recordIdle(WorkQueue.DEPLOYMENT_RUN);
        } else {
            pollGate.recordBusy(WorkQueue.DEPLOYMENT_RUN);
        }
        for (Long historyId : historyIds) {
            dispatchOne(historyId);
        }
    }

    private void dispatchOne(Long historyId) {
        try {
            log.info("배포 Job 실행 위임: historyId={} workerId={}", historyId, workerId);
            deploymentCommandService.executeQueued(historyId);
        } catch (RuntimeException exception) {
            // 실행기 포화(TaskRejectedException)든 제출 전 다른 예외든 결론은 같다 — 이 이력은
            // 돌지 않는다. 그렇다면 claim 을 붙들고 있을 이유가 없으므로 즉시 PENDING 으로
            // 되돌린다. 예외를 밖으로 흘리지 않는 것이 핵심이다: 흘리면 이 배치의 나머지가
            // claim 된 채 버려진다.
            handleDispatchFailure(historyId, exception);
        }
    }

    private void handleDispatchFailure(Long historyId, RuntimeException exception) {
        boolean released = deploymentHistoryRepository.releaseClaim(
                historyId, workerId, dispatchRejectBackoffMs);
        log.warn("배포 Job 위임 실패 — 재대기열로 반환합니다. historyId={} workerId={} released={} 원인={}",
                historyId, workerId, released, exception.toString());
    }

    @Scheduled(fixedDelayString = "${qeploy.deployment.worker.heartbeat-interval-ms:30000}")
    public void renewLeases() {
        deploymentHistoryRepository.renewLeases(workerId);
    }
}
