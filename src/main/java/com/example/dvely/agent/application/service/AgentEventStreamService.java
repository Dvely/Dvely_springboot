package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentTaskEvent;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.stream.AgentEventBus;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
public class AgentEventStreamService {

    private static final long STREAM_TIMEOUT_MS = 5 * 60 * 1000L;
    // 깨우기(AgentEventBus)가 닿지 않는 이벤트를 위한 폴백 간격 — 신호는 이 JVM 안에서만 돌기
    // 때문에, 다중 인스턴스로 늘리면 다른 인스턴스가 적재한 이벤트는 이 주기로만 보인다.
    private static final long FALLBACK_POLL_MS = 5_000L;
    // 주석 프레임. SSE 에서 ':' 로 시작하는 줄은 이벤트가 아니라 무시되지만, 바이트는 실제로
    // 나간다 — 그 쓰기가 응답 헤더를 내보내고 연결을 살아 있게 유지한다.
    private static final String HEARTBEAT_COMMENT = "hb";

    private final TaskStore taskStore;
    private final AgentEventBus eventBus;
    private final Executor eventExecutor;
    private final long heartbeatIntervalMs;

    public AgentEventStreamService(
            TaskStore taskStore,
            AgentEventBus eventBus,
            @Qualifier("agentEventExecutor") Executor eventExecutor,
            @Value("${qeploy.agent.stream.heartbeat-interval-ms:15000}") long heartbeatIntervalMs
    ) {
        this.taskStore = taskStore;
        this.eventBus = eventBus;
        this.eventExecutor = eventExecutor;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public SseEmitter open(Long ownerUserId, String taskId, Long afterEventId) {
        if (taskStore.getOwned(taskId, ownerUserId) == null) {
            return null;
        }
        SseEmitter emitter = newEmitter();
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
     * 테스트 이음새. "열리자마자 쓴다"(#370)는 계약을 검증하려면 emitter 에 무엇이 언제
     * 쓰였는지 봐야 하는데, Spring 이 그 관찰 지점({@code ResponseBodyEmitter.initialize})을
     * 패키지 전용으로 두고 있어 밖에서 붙일 수 없다. 테스트가 기록용 emitter 를 끼울 자리다.
     */
    SseEmitter newEmitter() {
        return new SseEmitter(STREAM_TIMEOUT_MS);
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
            // 열자마자 한 번 쓴다. 이 한 줄이 응답 헤더를 내보낸다 — 보낼 이벤트가 없는
            // 재연결(afterEventId 가 이미 최신 커서)에서는 아래 루프가 단 한 번도 send 하지
            // 않으므로, 이것이 없으면 5분 상한이 찰 때까지 1바이트도 나가지 않는다. 그러면
            // 중간 프록시는 "업스트림이 응답 헤더를 안 준다"고 보고 스스로 끊는다 — 운영
            // nginx 가 60초에 504 를 냈고(proxy_read_timeout), FE 는 연속 3회 실패로 폴링에
            // 내려앉은 뒤 그 태스크 동안 다시 올라오지 않았다(#370).
            emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
            long lastWriteAt = System.currentTimeMillis();
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
                        lastWriteAt = System.currentTimeMillis();
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
                lastWriteAt = maybeHeartbeat(emitter, active, lastWriteAt);
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

    /**
     * 유휴가 길어지면 주석 프레임으로 연결을 살아 있게 유지한다. 이벤트를 방금 보냈다면 그
     * 쓰기가 곧 하트비트이므로 건너뛴다 — 주기는 "마지막으로 무엇이든 쓴 시각" 기준이다.
     */
    private long maybeHeartbeat(SseEmitter emitter, AtomicBoolean active, long lastWriteAt)
            throws IOException {
        long now = System.currentTimeMillis();
        if (!active.get() || now - lastWriteAt < heartbeatIntervalMs) {
            return lastWriteAt;
        }
        emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
        return now;
    }

    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.DONE
                || status == TaskStatus.CANCELLED;
    }
}
