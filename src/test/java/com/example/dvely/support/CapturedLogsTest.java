package com.example.dvely.support;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CapturedLogsTest {

    private static final Logger log = LoggerFactory.getLogger(CapturedLogsTest.class);

    @Test
    void keepsOnlyWhatTheCallingThreadLoggedWhileAnotherThreadWritesToTheSameLogger() {
        // 캐시된 다른 컨텍스트의 워커가 같은 로거로 ERROR 를 남기는 상황을 그대로 만든다(AgentPlanExecutorTest, PR #689 CI).
        List<ILoggingEvent> logs = CapturedLogs.of(CapturedLogsTest.class, () -> {
            log.warn("이 스레드의 경고");
            Thread worker = new Thread(() -> log.error("다른 스레드의 오류"), "agent-9");
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        assertThat(logs).extracting(ILoggingEvent::getFormattedMessage).containsExactly("이 스레드의 경고");
        assertThat(logs).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    @Test
    void stopsListeningOnceTheCallReturnsEvenIfItThrows() {
        try {
            CapturedLogs.of(CapturedLogsTest.class, () -> {
                throw new IllegalStateException("부른 코드의 실패");
            });
        } catch (IllegalStateException expected) {
            // 던진 것은 그대로 올라온다
        }
        List<ILoggingEvent> later = CapturedLogs.of(CapturedLogsTest.class, () -> log.info("다음 호출"));

        assertThat(later).extracting(ILoggingEvent::getFormattedMessage).containsExactly("다음 호출");
        assertThat(((ch.qos.logback.classic.Logger) log).iteratorForAppenders().hasNext()).isFalse();
    }
}
