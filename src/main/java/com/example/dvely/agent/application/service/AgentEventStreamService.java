package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentTaskEvent;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.stream.AgentEventBus;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
public class AgentEventStreamService {

    private static final long STREAM_TIMEOUT_MS = 5 * 60 * 1000L;
    // 깨우기(AgentEventBus)가 닿지 않는 이벤트를 위한 폴백 간격 — 신호는 이 JVM 안에서만 돌기
    // 때문에, 다중 인스턴스로 늘리면 다른 인스턴스가 적재한 이벤트는 이 주기로만 보인다.
    private static final long FALLBACK_POLL_MS = 5_000L;

    private final TaskStore taskStore;
    private final AgentEventBus eventBus;
    private final Executor eventExecutor;

    public AgentEventStreamService(
            TaskStore taskStore,
            AgentEventBus eventBus,
            @Qualifier("agentEventExecutor") Executor eventExecutor
    ) {
        this.taskStore = taskStore;
        this.eventBus = eventBus;
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
        // 구독은 실행기에 넘기기 전에 연다 — 넘긴 뒤에 열면 그 사이에 적재된 이벤트의 깨우기를
        // 놓치고, 그 이벤트가 폴백 간격만큼 늦게 보인다.
        AgentEventBus.Subscription subscription = eventBus.subscribe(taskId);
        try {
            eventExecutor.execute(() -> stream(emitter, ownerUserId, taskId, afterEventId, active, subscription));
        } catch (RejectedExecutionException rejected) {
            // 동시 스트림 상한(AsyncConfig.agentEventExecutor)에 닿았다. 여기서 막고 기다리면
            // 요청 스레드가 스트림 수명만큼 묶이므로, 열어 주지 않고 FE 의 폴링 강등에 맡긴다.
            subscription.close();
            log.warn("[AgentEventStream] 동시 스트림 상한 도달 — 스트림을 열지 않는다. taskId={}", taskId);
            return null;
        }
        return emitter;
    }

    /**
     * 스트림 한 건. 매 틱 한 쿼리(#339 4-1)로 "끝났나 / 새 이벤트가 있나"를 묻고, 볼 것이 있을
     * 때만 이벤트를 읽는다. 대기는 깨우기 신호로 끊긴다(#339 4-2).
     */
    private void stream(SseEmitter emitter,
                        Long ownerUserId,
                        String taskId,
                        Long initialEventId,
                        AtomicBoolean active,
                        AgentEventBus.Subscription subscription) {
        long lastEventId = initialEventId == null ? 0L : initialEventId;
        try (subscription) {
            while (active.get()) {
                TaskStore.StreamState state = taskStore.getStreamState(taskId, ownerUserId);
                if (state == null) {
                    // 태스크가 사라졌거나 더는 이 사용자의 것이 아니다 — 보낼 것이 없다.
                    if (active.compareAndSet(true, false)) {
                        emitter.complete();
                    }
                    return;
                }
                if (state.lastEventId() > lastEventId) {
                    for (AgentTaskEvent event : taskStore.getEventsSince(taskId, lastEventId)) {
                        if (!active.get()) {
                            return;   // 콜백이 이미 닫은 emitter 로 send 하지 않는다
                        }
                        emitter.send(SseEmitter.event()
                                .id(String.valueOf(event.eventId()))
                                .name(event.type())
                                .data(event));
                        lastEventId = event.eventId();
                    }
                }
                // 상태와 마지막 이벤트 번호를 한 문장으로 읽었으므로, 여기서 terminal 이라는 것은
                // 그 종료 이벤트도 위 조회에 이미 들어왔다는 뜻이다(둘이 같은 트랜잭션에서 커밋된다).
                if (isTerminal(state.status())) {
                    if (active.compareAndSet(true, false)) {
                        emitter.complete();
                    }
                    return;
                }
                subscription.await(FALLBACK_POLL_MS);
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
