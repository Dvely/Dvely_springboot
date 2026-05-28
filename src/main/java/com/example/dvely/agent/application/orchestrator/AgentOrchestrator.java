package com.example.dvely.agent.application.orchestrator;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final AgentPlanExecutor agentPlanExecutor;
    private final TaskStore         taskStore;

    public String submitAsync(AgentPlan plan, Long userId) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        taskStore.save(new AgentTask(taskId, TaskStatus.PENDING, null, null, null, null, Instant.now()));
        agentPlanExecutor.execute(plan, taskId, userId);
        return taskId;
    }
}
