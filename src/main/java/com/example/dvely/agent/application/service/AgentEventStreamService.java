package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskEvent;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
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
        // active=false 로 내려가면 스트리밍 루프가 멈추고, 콜백이 emitter 를 이미 닫았다는 표시도 겸한다.
        AtomicBoolean active = new AtomicBoolean(true);
        // 유휴로 스트림이 타임아웃되면(WAITING 상태에서 새 이벤트가 한동안 없을 때) 기본 동작은
        // AsyncRequestTimeoutException 을 던져 ERROR + "response already committed" 노이즈를 남긴다(#4).
        // 대신 조용히 complete 한다 — EventSource 가 알아서 재연결하므로 기능 손실이 없다.
        emitter.onTimeout(() -> {
            if (active.compareAndSet(true, false)) {
                emitter.complete();
            }
        });
        // 클라이언트 끊김·전송 오류로 컨테이너가 스트림을 끝내면 루프도 멈춘다(스레드 누수 방지).
        emitter.onError(exception -> active.set(false));
        emitter.onCompletion(() -> active.set(false));
        eventExecutor.execute(() -> stream(emitter, ownerUserId, taskId, afterEventId, active));
        return emitter;
    }

    private void stream(SseEmitter emitter,
                        Long ownerUserId,
                        String taskId,
                        Long initialEventId,
                        AtomicBoolean active) {
        long lastEventId = initialEventId == null ? 0L : initialEventId;
        try {
            while (active.get()) {
                List<AgentTaskEvent> events = taskStore.getEvents(taskId, ownerUserId, lastEventId);
                for (AgentTaskEvent event : events) {
                    if (!active.get()) {
                        return;   // 콜백이 이미 닫은 emitter 로 send 하지 않는다
                    }
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(event.eventId()))
                            .name(event.type())
                            .data(event));
                    lastEventId = event.eventId();
                }
                AgentTask task = taskStore.getOwned(taskId, ownerUserId);
                if (task == null || isTerminal(task.status())) {
                    if (active.compareAndSet(true, false)) {
                        emitter.complete();
                    }
                    return;
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (active.compareAndSet(true, false)) {
                emitter.complete();
            }
        } catch (Exception exception) {
            // 클라이언트 끊김(IOException) 등. 콜백(onTimeout/onError)이 이미 닫았으면 active=false 라
            // 아무것도 하지 않는다 — 이미 완료된 emitter 를 다시 닫으려다 IllegalState 를 내지 않게.
            if (active.compareAndSet(true, false)) {
                emitter.completeWithError(exception);
            }
        }
    }

    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.DONE
                || status == TaskStatus.CANCELLED;
    }
}
