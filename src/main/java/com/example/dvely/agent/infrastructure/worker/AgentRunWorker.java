package com.example.dvely.agent.infrastructure.worker;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.orchestrator.AgentPlanExecutor;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.lang.management.ManagementFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunWorker {

    private static final int CLAIM_BATCH_SIZE = 2;

    private final TaskStore taskStore;
    private final AgentPlanExecutor agentPlanExecutor;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();

    @Scheduled(fixedDelayString = "${qeploy.agent.worker.poll-interval-ms:1000}")
    public void dispatchQueuedRuns() {
        taskStore.recoverExpiredLeases();
        List<String> taskIds = taskStore.claimRunnableTasks(workerId, CLAIM_BATCH_SIZE);
        for (String taskId : taskIds) {
            AgentTask task = taskStore.get(taskId);
            AgentPlan plan = taskStore.getPlan(taskId);
            if (task == null || plan == null) {
                taskStore.markFailed(taskId, "저장된 Agent 실행 계획을 찾을 수 없습니다.");
                continue;
            }
            log.info("[AgentRunWorker] task 실행 위임: taskId={} workerId={}", taskId, workerId);
            agentPlanExecutor.execute(plan, taskId, task.ownerUserId());
        }
    }

    @Scheduled(fixedDelayString = "${qeploy.agent.worker.heartbeat-interval-ms:30000}")
    public void renewLeases() {
        taskStore.renewWorkerLeases(workerId);
    }
}
