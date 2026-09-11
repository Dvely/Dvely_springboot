package com.example.dvely.agent.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskEvent;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.facade.AgentFacade;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.AgentEventStreamService;
import com.example.dvely.agent.application.service.AiProviderQueryService;
import com.example.dvely.agent.application.stream.AgentEventAppendedEvent;
import com.example.dvely.agent.application.stream.AgentEventBus;
import com.example.dvely.agent.infrastructure.config.AsyncConfig;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.common.response.ApiResponseAdvice;
import com.example.dvely.preview.application.service.PreviewSessionService;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * #339 의 핵심 증거 — 동시 스트림 5 개가 <b>전부 실시간으로</b> 수신된다.
 *
 * <p>예전 {@code agentEventExecutor}(core2/max10/queue100)에서는 이 단언이 깨진다.
 * {@code ThreadPoolExecutor} 가 큐를 다 채운 뒤에야 코어를 넘겨 스레드를 늘리기 때문에, 3 번째
 * 스트림부터는 앞 스트림이 5 분 타임아웃을 채울 때까지 큐에서 시작조차 되지 않았다 — 그 동안
 * FE 는 스트림이 열리지 않아 조용히 5 초 폴링으로 강등됐다.</p>
 *
 * <p>DB 는 모킹한다. 여기서 증명하려는 것은 쿼리의 정확성이 아니라 <b>동시성과 전달 지연</b>이고,
 * 그 둘은 실제 실행기·실제 신호선·실제 HTTP 응답 버퍼를 그대로 써야 드러난다. 쿼리 쪽은
 * {@code AgentEventStreamStateIntegrationTest} 가 실제 MySQL 로 본다.</p>
 */
class AgentEventStreamConcurrencyContractTest {

    private static final Long USER_ID = 1L;
    private static final int STREAMS = 5;

    private final Map<String, List<AgentTaskEvent>> appended = new ConcurrentHashMap<>();
    private final AtomicLong eventIds = new AtomicLong();

    private TaskStore taskStore;
    private AgentEventBus eventBus;
    private SimpleAsyncTaskExecutor executor;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        taskStore = mock(TaskStore.class);
        when(taskStore.getOwned(anyString(), eq(USER_ID))).thenAnswer(invocation -> new AgentTask(
                invocation.getArgument(0), USER_ID, null, null, TaskStatus.RUNNING,
                null, null, null, null, Instant.now()));
        when(taskStore.getStreamState(anyString(), eq(USER_ID))).thenAnswer(invocation ->
                new TaskStore.StreamState(TaskStatus.RUNNING, lastEventId(invocation.getArgument(0))));
        when(taskStore.getEventsSince(anyString(), anyLong())).thenAnswer(invocation -> {
            long after = invocation.getArgument(1);
            return eventsOf(invocation.getArgument(0)).stream()
                    .filter(event -> event.eventId() > after)
                    .toList();
        });

        eventBus = new AgentEventBus();
        executor = new AsyncConfig().agentEventExecutor(50);
        AgentEventStreamService streamService =
                new AgentEventStreamService(taskStore, eventBus, executor);

        mockMvc = MockMvcBuilders.standaloneSetup(new AgentController(
                        mock(AgentFacade.class),
                        mock(AgentOrchestrator.class),
                        taskStore,
                        mock(PreviewSessionService.class),
                        streamService,
                        mock(AiProviderQueryService.class)))
                .setControllerAdvice(new ApiResponseAdvice())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter,
                                                  ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest,
                                                  WebDataBinderFactory binderFactory) {
                        return USER_ID;
                    }
                })
                .build();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void 동시_스트림_다섯개가_전부_실시간으로_이벤트를_받는다() throws Exception {
        List<MvcResult> streams = new ArrayList<>();
        for (int i = 0; i < STREAMS; i++) {
            streams.add(openStream("task-" + i));
        }
        // 다섯 스트림이 전부 루프에 들어갔는가 — 예전 실행기라면 두 개만 들어간다.
        for (int i = 0; i < STREAMS; i++) {
            awaitLoopRunning("task-" + i);
        }

        for (int i = 0; i < STREAMS; i++) {
            appendAndSignal("task-" + i, "진행-" + i);
        }

        for (int i = 0; i < STREAMS; i++) {
            awaitBodyContains(streams.get(i), "진행-" + i);
        }
    }

    @Test
    void 유휴_스트림은_매초_DB_를_다시_묻지_않는다() throws Exception {
        openStream("task-idle");
        awaitLoopRunning("task-idle");

        // 폴백 간격(5초)보다 짧게 기다린다. 예전 루프였다면 이 사이에 초당 3 쿼리가 나갔다.
        TimeUnit.MILLISECONDS.sleep(1_500);

        verify(taskStore, times(1)).getStreamState("task-idle", USER_ID);
        verify(taskStore, times(1)).getOwned("task-idle", USER_ID);
    }

    @Test
    void 이벤트가_오면_한_틱에_한_번만_더_묻는다() throws Exception {
        MvcResult stream = openStream("task-1");
        awaitLoopRunning("task-1");

        appendAndSignal("task-1", "진행-1");
        awaitBodyContains(stream, "진행-1");

        // 최초 1회 + 깨어나서 1회. 매초 폴링이 남아 있었다면 이보다 커진다.
        verify(taskStore, times(2)).getStreamState("task-1", USER_ID);
    }

    private MvcResult openStream(String taskId) throws Exception {
        return mockMvc.perform(get("/api/v1/agent/tasks/{taskId}/events/stream", taskId)).andReturn();
    }

    private void appendAndSignal(String taskId, String message) {
        eventsOf(taskId).add(new AgentTaskEvent(
                eventIds.incrementAndGet(), taskId, "STEP_STARTED", TaskStatus.RUNNING,
                message, null, null, null, null));
        eventBus.onAgentEventAppended(new AgentEventAppendedEvent(taskId));
    }

    private List<AgentTaskEvent> eventsOf(String taskId) {
        return appended.computeIfAbsent(taskId, key -> new CopyOnWriteArrayList<>());
    }

    private long lastEventId(String taskId) {
        List<AgentTaskEvent> events = eventsOf(taskId);
        return events.isEmpty() ? 0L : events.get(events.size() - 1).eventId();
    }

    /** 스트림 루프가 첫 조회를 낼 때까지 기다린다 — 실행기에 실려 실제로 돌기 시작했다는 뜻이다. */
    private void awaitLoopRunning(String taskId) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            try {
                verify(taskStore).getStreamState(taskId, USER_ID);
                return;
            } catch (AssertionError notYet) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
        }
        throw new AssertionError("스트림 루프가 시작되지 않았다: " + taskId);
    }

    /** MockHttpServletResponse 는 기본 charset 으로 디코딩한다 — SSE 본문은 UTF-8 이므로 명시한다. */
    private void awaitBodyContains(MvcResult stream, String expected)
            throws InterruptedException, UnsupportedEncodingException {
        for (int i = 0; i < 300; i++) {
            if (body(stream).contains(expected)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(body(stream)).contains(expected);
    }

    private String body(MvcResult stream) throws UnsupportedEncodingException {
        return stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
