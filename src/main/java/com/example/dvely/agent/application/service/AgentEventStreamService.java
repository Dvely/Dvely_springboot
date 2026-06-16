package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskEvent;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AgentEventStreamService {

    private static final long STREAM_TIMEOUT_MS = 5 * 60 * 1000L;

    private final TaskStore taskStore;
    private final Executor eventExecutor;

    public AgentEventStreamService(
            TaskStore taskStore,
            @Qualifier("agentEventExecutor") Executor eventExecutor
    ) {
        this.taskStore = taskStore;
        this.eventExecutor = eventExecutor;
    }

    public SseEmitter open(Long ownerUserId, String taskId, Long afterEventId) {
        if (taskStore.getOwned(taskId, ownerUserId) == null) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        eventExecutor.execute(() -> stream(emitter, ownerUserId, taskId, afterEventId));
        return emitter;
    }

    private void stream(SseEmitter emitter,
                        Long ownerUserId,
                        String taskId,
                        Long initialEventId) {
        long lastEventId = initialEventId == null ? 0L : initialEventId;
        try {
            while (true) {
                List<AgentTaskEvent> events = taskStore.getEvents(taskId, ownerUserId, lastEventId);
                for (AgentTaskEvent event : events) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(event.eventId()))
                            .name(event.type())
                            .data(event));
                    lastEventId = event.eventId();
                }
                AgentTask task = taskStore.getOwned(taskId, ownerUserId);
                if (task == null || isTerminal(task.status())) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        } catch (RuntimeException exception) {
            emitter.completeWithError(exception);
        }
    }

    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.DONE
                || status == TaskStatus.CANCELLED;
    }
}
