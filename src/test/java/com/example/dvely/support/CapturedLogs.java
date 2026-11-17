package com.example.dvely.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * 한 클래스의 로거가 {@code call} 동안 <b>부른 스레드에서</b> 남긴 로그를 받는다.
 *
 * <p>로거는 테스트 JVM 에 하나뿐이라, 캐시된 다른 Spring 컨텍스트의 스케줄 워커·{@code @Async} 실행기가 같은 클래스로 남긴
 * 로그도 같은 appender 에 들어온다. 실측(2026-11-17): {@code OrchestrationConcurrencyIntegrationTest} 가 재시도·회수로
 * QUEUED 에 돌려놓은 태스크를 {@code AgentRunWorker} 가 1~3초 뒤 {@code agent-N} 스레드에서 실행하고, 키가 없어
 * {@code AgentPlanExecutor} 로거에 ERROR("AgentPlan 실행 실패")를 남긴다. 그 한 줄이 {@code AgentPlanExecutorTest} 의
 * "ERROR 가 없다" 검사 사이에 떨어져 PR #689 의 CI 첫 실행이 깨졌다.</p>
 *
 * <p>동기로 부른 코드가 남긴 로그만 보려면 스레드로 거른다. {@link SentSql} 과 같은 이유다. 검사하는 코드가 다른 스레드에서
 * 로그를 남긴다면(실행기에 넘기는 코드) 이 도우미는 맞지 않는다.</p>
 */
public final class CapturedLogs {

    private CapturedLogs() {
    }

    /** {@code owner} 의 로거가 {@code call} 동안 이 스레드에서 남긴 로그, 남긴 순서대로. */
    public static List<ILoggingEvent> of(Class<?> owner, Runnable call) {
        String thread = Thread.currentThread().getName();
        Logger logger = (Logger) LoggerFactory.getLogger(owner);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            call.run();
        } finally {
            logger.detachAppender(appender);
        }
        // AppenderBase.doAppend 가 동기화돼 있어 다른 스레드가 같이 쓴 목록도 여기서 안전하게 읽는다.
        synchronized (appender) {
            return appender.list.stream()
                    .filter(event -> thread.equals(event.getThreadName()))
                    .toList();
        }
    }
}
