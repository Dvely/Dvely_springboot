package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.stream.AgentEventBus;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * #370 회귀망. 보낼 이벤트가 없는 재연결이 응답 헤더를 못 내보내 중간 프록시에 끊기던 사고를
 * 막는다 — 2026-09-18 운영에서 nginx 가 60초마다 504 를 냈고, FE 는 연속 3회 실패로 폴링에
 * 내려앉은 뒤 그 태스크 동안 스트림을 다시 열지 않았다.
 */
class AgentEventStreamHeartbeatTest {

    private static final String TASK_ID = "t-1";
    private static final Long OWNER = 7L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 무엇이 언제 쓰였는지 기록하는 emitter. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> frames = new CopyOnWriteArrayList<>();

        RecordingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder sb = new StringBuilder();
            builder.build().forEach(part -> sb.append(part.getData()));
            frames.add(sb.toString());
        }

        @Override
        public void complete() {
            // 테스트에서는 실제 비동기 응답이 없으므로 아무것도 하지 않는다.
        }
    }

    private AgentEventStreamService serviceWith(RecordingEmitter emitter,
                                                TaskStore taskStore,
                                                long heartbeatIntervalMs) {
        return new AgentEventStreamService(taskStore, new AgentEventBus(), executor, heartbeatIntervalMs) {
            @Override
            SseEmitter newEmitter() {
                return emitter;
            }
        };
    }

    private TaskStore idleTaskStore() {
        TaskStore taskStore = mock(TaskStore.class);
        when(taskStore.getOwned(TASK_ID, OWNER)).thenReturn(mock(AgentTask.class));
        // 커서가 이미 최신 — 보낼 이벤트가 없다. 이것이 운영에서 504 를 내던 조건이다.
        when(taskStore.getStreamState(TASK_ID, OWNER))
                .thenReturn(new TaskStore.StreamState(TaskStatus.RUNNING, 190L));
        when(taskStore.getEventsSince(anyString(), anyLong())).thenReturn(List.of());
        return taskStore;
    }

    @Test
    @DisplayName("보낼 이벤트가 없어도 스트림을 열자마자 바이트를 쓴다 — 응답 헤더가 나가야 한다")
    void 보낼_이벤트가_없어도_열자마자_바이트를_쓴다() {
        RecordingEmitter emitter = new RecordingEmitter();
        AgentEventStreamService service = serviceWith(emitter, idleTaskStore(), 60_000L);

        assertThat(service.open(OWNER, TASK_ID, 190L)).isNotNull();

        // 하트비트 주기(60초)가 돌아오기 한참 전에 이미 쓰여 있어야 한다.
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(emitter.frames).isNotEmpty());
        assertThat(emitter.frames.getFirst())
                .as("주석 프레임이어야 하고 data: 줄이 없어야 한다 — FE 파서가 무시한다")
                .startsWith(":")
                .doesNotContain("data:");
    }

    @Test
    @DisplayName("유휴가 이어지면 하트비트를 반복해 연결을 살려둔다")
    void 유휴가_이어지면_하트비트를_반복한다() {
        RecordingEmitter emitter = new RecordingEmitter();
        // 0 이면 매 루프마다 주기가 지난 것으로 본다.
        AgentEventStreamService service = serviceWith(emitter, idleTaskStore(), 0L);

        service.open(OWNER, TASK_ID, 190L);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(emitter.frames).hasSizeGreaterThanOrEqualTo(2));
        assertThat(emitter.frames).allSatisfy(frame -> assertThat(frame).startsWith(":"));
    }

    @Test
    @DisplayName("주기가 멀면 여는 첫 쓰기 말고는 더 쓰지 않는다 — 쓸데없이 깨우지 않는다")
    void 주기가_돌아오기_전에는_더_쓰지_않는다() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        AgentEventStreamService service = serviceWith(emitter, idleTaskStore(), 60_000L);

        service.open(OWNER, TASK_ID, 190L);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(emitter.frames).isNotEmpty());

        // 루프가 최소 한 바퀴(FALLBACK_POLL_MS=5초)는 돌 만큼 기다린다.
        Thread.sleep(7_000L);
        assertThat(emitter.frames).as("60초 주기이므로 추가 쓰기가 없어야 한다").hasSize(1);
    }

    @Test
    @DisplayName("소유자가 아니면 스트림을 열지 않는다 — 기존 계약")
    void 소유자가_아니면_스트림을_열지_않는다() {
        TaskStore taskStore = mock(TaskStore.class);
        when(taskStore.getOwned(anyString(), any())).thenReturn(null);
        RecordingEmitter emitter = new RecordingEmitter();

        assertThat(serviceWith(emitter, taskStore, 15_000L).open(OWNER, TASK_ID, 0L)).isNull();
        assertThat(emitter.frames).isEmpty();
    }
}
