package com.example.dvely.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * 코드가 Hibernate 로 <b>실제로 보낸</b> SQL 을 받는다. 손으로 옮긴 SQL 을 EXPLAIN 하면, 코드의 쿼리가 바뀌어도 테스트는 옛 SQL 을
 * 계속 본다.
 */
public final class SentSql {

    private SentSql() {
    }

    /**
     * {@code call} 이 보낸 SQL 을 보낸 순서대로, 앞뒤 공백만 걷어 돌려준다. Hibernate 의 SQL 로거({@code org.hibernate.SQL})를
     * 잠시 DEBUG 로 내려 받는다.
     *
     * <p>호출한 스레드가 보낸 것만 남긴다. 같은 컨텍스트의 스케줄 워커도 같은 로거로 SQL 을 남기므로, 거르지 않으면 그 사이 워커가
     * 보낸 문장이 섞인다.</p>
     */
    public static List<String> capture(Runnable call) {
        String thread = Thread.currentThread().getName();
        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level original = sqlLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sqlLogger.addAppender(appender);
        sqlLogger.setLevel(Level.DEBUG);
        try {
            call.run();
        } finally {
            sqlLogger.setLevel(original);
            sqlLogger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(event -> thread.equals(event.getThreadName()))
                .map(event -> event.getFormattedMessage().strip())
                .toList();
    }
}
