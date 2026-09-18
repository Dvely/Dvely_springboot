package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.llm.LlmRouter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * U8 8-7: 계획 응답 원문을 INFO 로 통째로 찍던 자리를 막았는지 본다.
 *
 * <p>계획 JSON 에는 사용자가 무엇을 만들라고 했는지가 그대로 들어가고, 교정 재시도 로그에는
 * 모델이 쓴 응답이 통째로 들어간다 — 운영 로그 수집기로 사용자 요청과 생성 코드가 흘러나가는
 * 경로였다. 실제로 찍히는 것을 보는 것 말고 검증할 방법이 없어 로그를 가로챈다.</p>
 */
class DecisionAgentLoggingTest {

    /** 로그에 절대 통째로 나오면 안 되는, 사용자 요청과 코드가 섞인 응답. */
    private static final String SECRET_LOOKING_PLAN = """
            {"reasoning":"사내 재고 관리 앱을 만든다",
             "steps":[{"agentType":"CODE","parameters":{
                "instruction":"%s",
                "userSummary":"재고 페이지를 만듭니다"}}]}
            """.formatted("const INTERNAL_ENDPOINT = 'https://inventory.corp.example/api'; "
            .repeat(20));

    private final LlmRouter llmRouter = mock(LlmRouter.class);
    private final LlmPort llmPort = mock(LlmPort.class);
    private final ProjectDecisionContextResolver contextResolver =
            mock(ProjectDecisionContextResolver.class);
    private final DecisionAgentService service =
            new DecisionAgentService(llmRouter, contextResolver);

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(DecisionAgentService.class);
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
    }

    @Test
    void neverLogsTheWholeResponseAtInfo() {
        when(llmRouter.route(AiProvider.GLM)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList(), any())).thenReturn(SECRET_LOOKING_PLAN);

        service.decide(List.of(new LlmMessage("user", "재고 앱 만들어줘")), AiProvider.GLM, null);

        List<ILoggingEvent> infoAndAbove = appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .toList();

        assertThat(infoAndAbove).isNotEmpty();
        assertThat(infoAndAbove).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("INTERNAL_ENDPOINT"));
        // 대신 길이는 남는다 — 응답이 잘렸는지 같은 운영 질문에는 답할 수 있어야 한다.
        assertThat(infoAndAbove).anySatisfy(event ->
                assertThat(event.getFormattedMessage())
                        .contains("rawLength=" + SECRET_LOOKING_PLAN.length()));
    }

    @Test
    void truncatesTheResponsePreviewEvenAtDebug() {
        when(llmRouter.route(AiProvider.GLM)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList(), any())).thenReturn(SECRET_LOOKING_PLAN);

        service.decide(List.of(new LlmMessage("user", "재고 앱 만들어줘")), AiProvider.GLM, null);

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .contains("자 중 앞부분")
                    .hasSizeLessThan(SECRET_LOOKING_PLAN.length());
        });
    }

    @Test
    void alsoTruncatesTheFailedResponseEchoedInTheRepairRetryWarning() {
        // 파싱 실패 경로가 원문을 가장 길게 찍던 곳이다 — 실패한 응답과 재시도 응답 둘 다.
        when(llmRouter.route(AiProvider.GLM)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList(), any()))
                .thenReturn("이건 JSON 이 아니다 " + "INTERNAL_ENDPOINT ".repeat(50));

        try {
            service.decide(List.of(new LlmMessage("user", "재고 앱")), AiProvider.GLM, null);
        } catch (RuntimeException expected) {
            // 재시도까지 실패하면 던지는 것이 정상이다(조용한 CHAT 폴백은 이미 걷어냈다).
        }

        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).hasSizeLessThan(600));
    }
}
