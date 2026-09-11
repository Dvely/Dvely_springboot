package com.example.dvely.agent.application.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskEvent;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.service.AgentEventStreamService;
import com.example.dvely.agent.infrastructure.config.AsyncConfig;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * 스트림 루프의 <b>끝나는 길</b>(#339). 이 테스트가 보는 것은 구독 누수다 — 루프가 어떤 이유로
 * 끝나든 {@link AgentEventBus} 에 구독이 남으면, 그 태스크의 이벤트가 올 때마다 아무도 읽지 않는
 * 큐를 영원히 깨우게 된다.
 *
 * <p>{@code subscriberCount} 가 package-private 이라 이 테스트만 볼 수 있다.</p>
 */
class AgentEventStreamLoopTest {

    private static final Long USER_ID = 1L;

    private TaskStore taskStore;
    private AgentEventBus eventBus;
    private SimpleAsyncTaskExecutor executor;
    private AgentEventStreamService streamService;

    @BeforeEach
    void setUp() {
        taskStore = mock(TaskStore.class);
        when(taskStore.getOwned(anyString(), eq(USER_ID))).thenAnswer(invocation -> new AgentTask(
                invocation.getArgument(0), USER_ID, null, null, TaskStatus.RUNNING,
                null, null, null, null, Instant.now()));
        eventBus = new AgentEventBus();
        executor = new AsyncConfig().agentEventExecutor(1);
        streamService = new AgentEventStreamService(taskStore, eventBus, executor);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void 스트림이_열려_있는_동안_태스크가_사라지면_다음_틱에_스스로_끝난다() throws Exception {
        AtomicReference<TaskStore.StreamState> state =
                new AtomicReference<>(new TaskStore.StreamState(TaskStatus.RUNNING, 0L));
        when(taskStore.getStreamState("task-1", USER_ID)).thenAnswer(invocation -> state.get());

        assertThat(streamService.open(USER_ID, "task-1", 0L)).isNotNull();
        awaitSubscribed("task-1");

        // 소유권이 회수되거나 행이 지워지면 프로젝션이 빈 결과가 된다.
        state.set(null);
        eventBus.onAgentEventAppended(new AgentEventAppendedEvent("task-1"));

        awaitUnsubscribed("task-1");
    }

    @Test
    void terminal_이면_종료_이벤트까지_보내고_끝난다() throws Exception {
        when(taskStore.getStreamState("task-1", USER_ID))
                .thenReturn(new TaskStore.StreamState(TaskStatus.DONE, 7L));
        when(taskStore.getEventsSince(anyString(), anyLong())).thenReturn(List.of(new AgentTaskEvent(
                7L, "task-1", "COMPLETED", TaskStatus.DONE, "끝", null, null, null, null)));

        assertThat(streamService.open(USER_ID, "task-1", 0L)).isNotNull();

        awaitUnsubscribed("task-1");
    }

    @Test
    void 동시_스트림_상한에_닿으면_스트림을_열지_않고_구독도_남기지_않는다() throws Exception {
        // 상한 1 짜리 실행기를 살아 있는 작업 하나로 채운다.
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertThat(streamService.open(USER_ID, "task-1", 0L)).isNull();
            assertThat(eventBus.subscriberCount("task-1")).isZero();
        } finally {
            release.countDown();
        }
    }

    private void awaitSubscribed(String taskId) throws InterruptedException {
        for (int i = 0; i < 500 && eventBus.subscriberCount(taskId) == 0; i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(eventBus.subscriberCount(taskId)).isOne();
    }

    private void awaitUnsubscribed(String taskId) throws InterruptedException {
        for (int i = 0; i < 500 && eventBus.subscriberCount(taskId) > 0; i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(eventBus.subscriberCount(taskId)).isZero();
    }
}
