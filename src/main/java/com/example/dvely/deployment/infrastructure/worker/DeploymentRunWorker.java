package com.example.dvely.deployment.infrastructure.worker;

import com.example.dvely.deployment.application.command.DeploymentCommandService;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import java.lang.management.ManagementFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeploymentRunWorker {

    private static final int CLAIM_BATCH_SIZE = 2;

    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final DeploymentCommandService deploymentCommandService;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-deployment";

    @Scheduled(fixedDelayString = "${qeploy.deployment.worker.poll-interval-ms:1000}")
    public void dispatchPendingDeployments() {
        deploymentHistoryRepository.recoverExpiredLeases();
        for (Long historyId : deploymentHistoryRepository.claimPending(workerId, CLAIM_BATCH_SIZE)) {
            log.info("배포 Job 실행 위임: historyId={} workerId={}", historyId, workerId);
            deploymentCommandService.executeQueued(historyId);
        }
    }

    @Scheduled(fixedDelayString = "${qeploy.deployment.worker.heartbeat-interval-ms:30000}")
    public void renewLeases() {
        deploymentHistoryRepository.renewLeases(workerId);
    }
}
