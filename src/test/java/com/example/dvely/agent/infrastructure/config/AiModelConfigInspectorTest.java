package com.example.dvely.agent.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 어긋난 설정은 기동 때 드러나야 한다. 드러내지 않으면 요청이 들어와 thinking 을 달라고 할 때에야
 * 400 으로 나타나는데, 그때는 설정을 바꾼 사람이 그 자리에 없다.
 */
class AiModelConfigInspectorTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(AiModelConfigInspector.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private AiProperties properties() {
        AiProperties properties = new AiProperties();
        // 기본값이 어긋나 있으면 다른 테스트가 읽기 어려워지므로, 검사 대상이 아닌 제공자는
        // thinking 을 비워 "이 제공자는 thinking 을 받지 않는다" 로 둔다.
        properties.getAnthropic().setThinkingModels(List.of());
        properties.getOpenai().setThinkingModels(List.of());
        properties.getGlm().setThinkingModels(List.of());
        return properties;
    }

    @Test
    @DisplayName("기본 모델이 thinking 목록에 없으면 경고한다 — 운영에서 실제로 난 형태")
    void warnsWhenDefaultModelCannotThink() {
        AiProperties properties = properties();
        properties.getGlm().setModel("z-ai/glm-5.3-flash");
        properties.getGlm().setThinkingModels(List.of("z-ai/glm-4.6"));

        new AiModelConfigInspector(properties).inspect();

        assertThat(warnings()).anySatisfy(message -> assertThat(message)
                .contains("GLM")
                .contains("z-ai/glm-5.3-flash")
                .contains("thinking"));
    }

    @Test
    @DisplayName("아무도 지정할 수 없는 thinking 모델을 경고한다 — 모델을 바꾸며 남은 흔적")
    void warnsWhenThinkingModelIsUnreachable() {
        AiProperties properties = properties();
        properties.getGlm().setModel("z-ai/glm-5.3-flash");
        properties.getGlm().setThinkingModels(List.of("z-ai/glm-5.3-flash", "z-ai/glm-4.6"));

        new AiModelConfigInspector(properties).inspect();

        assertThat(warnings()).anySatisfy(message -> assertThat(message)
                .contains("지정할 수 없는")
                .contains("z-ai/glm-4.6"));
    }

    @Test
    @DisplayName("allowed-models 에 있으면 지정할 수 있으므로 경고하지 않는다")
    void staysQuietWhenThinkingModelIsAllowed() {
        AiProperties properties = properties();
        properties.getGlm().setModel("z-ai/glm-5.3-flash");
        properties.getGlm().setAllowedModels(List.of("z-ai/glm-4.6"));
        properties.getGlm().setThinkingModels(List.of("z-ai/glm-5.3-flash", "z-ai/glm-4.6"));

        new AiModelConfigInspector(properties).inspect();

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("thinking 목록이 비어 있으면 아무 말도 하지 않는다 — gpt-4o 처럼 의도적인 선언이다")
    void staysQuietWhenThinkingIsDeliberatelyEmpty() {
        AiProperties properties = properties();
        properties.getOpenai().setModel("gpt-4o");

        new AiModelConfigInspector(properties).inspect();

        assertThat(warnings()).isEmpty();
    }
}
