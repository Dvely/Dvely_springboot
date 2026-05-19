package com.example.dvely.agent.infrastructure.store;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class TaskStore {

    private final ConcurrentHashMap<String, AgentTask> store = new ConcurrentHashMap<>();

    public void save(AgentTask task) {
        store.put(task.taskId(), task);
    }

    public AgentTask get(String taskId) {
        return store.get(taskId);
    }

    public void markDone(String taskId, String previewUrl, String summary) {
        update(taskId, TaskStatus.DONE, previewUrl, summary, null);
    }

    public void markFailed(String taskId, String error) {
        update(taskId, TaskStatus.FAILED, null, null, error);
    }

    public void markWaitingInput(String taskId, String question) {
        store.computeIfPresent(taskId, (k, existing) -> existing.withWaitingInput(question));
    }

    private void update(String taskId, TaskStatus status, String previewUrl, String summary, String error) {
        store.computeIfPresent(taskId, (k, existing) ->
                existing.withStatus(status, previewUrl, summary, error));
    }
}
