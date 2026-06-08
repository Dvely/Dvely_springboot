package com.example.dvely.agent.infrastructure.store;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.TaskStatus;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class TaskStore {

    private final ConcurrentHashMap<String, AgentTask> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentPlan> plans = new ConcurrentHashMap<>();

    public void save(AgentTask task) {
        store.put(task.taskId(), task);
    }

    public AgentTask getOwned(String taskId, Long ownerUserId) {
        AgentTask task = store.get(taskId);
        if (task == null || !task.ownerUserId().equals(ownerUserId)) {
            return null;
        }
        return task;
    }

    public AgentTask get(String taskId) {
        return store.get(taskId);
    }

    public void savePlan(String taskId, AgentPlan plan) {
        plans.put(taskId, plan);
    }

    public AgentPlan getPlan(String taskId) {
        return plans.get(taskId);
    }

    public void removePlan(String taskId) {
        plans.remove(taskId);
    }

    public void markWaitingApproval(String taskId, String summary) {
        update(taskId, TaskStatus.WAITING_APPROVAL, null, summary, null);
    }

    public void markRunning(String taskId) {
        store.computeIfPresent(taskId, (key, existing) ->
                existing.status() == TaskStatus.CANCELLED
                        ? existing
                        : existing.withStatus(TaskStatus.RUNNING, null, null, null));
    }

    public void markDone(String taskId, String previewUrl, String summary) {
        update(taskId, TaskStatus.DONE, previewUrl, summary, null);
    }

    public void markFailed(String taskId, String error) {
        update(taskId, TaskStatus.FAILED, null, null, error);
    }

    public void markWaitingInput(String taskId, String question) {
        store.computeIfPresent(taskId, (key, existing) ->
                existing.status() == TaskStatus.CANCELLED
                        ? existing
                        : existing.withWaitingInput(question));
    }

    public boolean cancel(String taskId, Long ownerUserId) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        store.computeIfPresent(taskId, (key, existing) -> {
            if (!existing.ownerUserId().equals(ownerUserId)
                    || existing.status() == TaskStatus.DONE
                    || existing.status() == TaskStatus.FAILED
                    || existing.status() == TaskStatus.CANCELLED) {
                return existing;
            }
            cancelled.set(true);
            plans.remove(taskId);
            return existing.withStatus(TaskStatus.CANCELLED, null, null, null);
        });
        return cancelled.get();
    }

    public boolean isCancelled(String taskId) {
        AgentTask task = store.get(taskId);
        return task != null && task.status() == TaskStatus.CANCELLED;
    }

    private void update(String taskId, TaskStatus status, String previewUrl, String summary, String error) {
        store.computeIfPresent(taskId, (k, existing) ->
                existing.status() == TaskStatus.CANCELLED
                        ? existing
                        : existing.withStatus(status, previewUrl, summary, error));
    }
}
