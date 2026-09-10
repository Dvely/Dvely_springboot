package com.example.dvely.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
import com.example.dvely.audit.infrastructure.config.AuditExecutorConfig;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Review follow-up (Medium-1, ad-audit-review.md): pins the {@code AUDIT_FALLBACK} log line's
 * actual content with a real Logback {@link ListAppender} capture (mirrors the reviewer's own
 * reproduction technique, §2 of the review) — a plain {@code verify(log)} call cannot catch a
 * missing format-string placeholder the way asserting on the rendered message text can.
 *
 * <p>10-4 이후로는 여기에 비동기 계약도 함께 고정한다: 쓰기는 요청 스레드를 떠나되, 어떤 실패도
 * 호출자에게 전파되지 않고 어떤 이벤트도 조용히 사라지지 않아야 한다.</p>
 */
class AuditRecorderTest {

    /** 실행기를 실제로 쓰는 테스트가 자기 것을 여기 담아두면 끝나고 정리된다. */
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    private ThreadPoolTaskExecutor realExecutor() {
        executor = (ThreadPoolTaskExecutor) new AuditExecutorConfig().auditLogExecutor();
        return executor;
    }

    private static AuditEvent event(String errorSummary) {
        return new AuditEvent(
                AuditAction.DEPLOYMENT_FAILED,
                AuditOutcome.FAILED,
                AuditActorType.SYSTEM,
                1L, 2L, "DEPLOYMENT", "500", null, null, "some-detail-value",
                errorSummary
        );
    }

    @Test
    void fallbackLogIncludesRedactedErrorSummaryWithoutLeakingTheRawSecret() {
        AuditLogWriter writer = mock(AuditLogWriter.class);
        doThrow(new RuntimeException("write failed")).when(writer).write(any());
        // 로그 캡처가 결정적이어야 하므로 이 테스트만 호출 스레드에서 실행한다.
        AuditRecorder recorder = new AuditRecorder(writer, Runnable::run);

        Logger logbackLogger = (Logger) LoggerFactory.getLogger(AuditRecorder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            // A FAILED-outcome event with a secret-shaped errorSummary — exactly the H9/H8/H12
            // shape the review flagged: this field is the one piece of information the fallback
            // log exists to preserve when the real audit write itself fails.
            recorder.record(event("deployment failed: token=" + "ghp_shouldnotleak1234567890" + " rejected"));

            assertThat(appender.list).hasSize(1);
            String formatted = appender.list.get(0).getFormattedMessage();
            assertThat(formatted)
                    .contains("errorSummary=")
                    .contains("***REDACTED***")
                    .doesNotContain("ghp_shouldnotleak1234567890");
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }

    @Test
    void theInsertHappensOnTheAuditThreadNotTheCallersThread() throws Exception {
        // 이 단위의 목적. 요청 스레드가 감사 INSERT 를 기다리며 커넥션을 하나 더 붙잡고 있지 않아야 한다.
        AuditLogWriter writer = mock(AuditLogWriter.class);
        AtomicReference<String> writingThread = new AtomicReference<>();
        CountDownLatch written = new CountDownLatch(1);
        doAnswer(invocation -> {
            writingThread.set(Thread.currentThread().getName());
            written.countDown();
            return null;
        }).when(writer).write(any());

        new AuditRecorder(writer, realExecutor()).record(event(null));

        assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(writingThread.get())
                .startsWith("audit-")
                .isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    void aWriteFailureOnTheAuditThreadNeverReachesTheCaller() {
        AuditLogWriter writer = mock(AuditLogWriter.class);
        doThrow(new RuntimeException("write failed")).when(writer).write(any());
        AuditRecorder recorder = new AuditRecorder(writer, realExecutor());

        assertThatCode(() -> recorder.record(event(null))).doesNotThrowAnyException();
    }

    @Test
    void aRejectedSubmissionStillWritesInsteadOfDisappearing() {
        // 실행기가 이미 내려간 뒤에 들어온 이벤트. 조용히 사라지면 감사의 의미가 없다.
        AuditLogWriter writer = mock(AuditLogWriter.class);
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> {
            writes.incrementAndGet();
            return null;
        }).when(writer).write(any());
        Executor refusing = task -> {
            throw new IllegalStateException("executor is gone");
        };

        assertThatCode(() -> new AuditRecorder(writer, refusing).record(event(null)))
                .doesNotThrowAnyException();

        assertThat(writes.get()).isEqualTo(1);
    }

    @Test
    void theRequestIdFollowsTheEventOntoTheAuditThread() throws Exception {
        // 감사 스레드가 남기는 로그가 어느 요청의 것인지 알 수 없으면 요청 ID 를 심은 의미가 없다.
        AuditLogWriter writer = mock(AuditLogWriter.class);
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch written = new CountDownLatch(1);
        doAnswer(invocation -> {
            seen.set(MDC.get("requestId"));
            written.countDown();
            return null;
        }).when(writer).write(any());
        MDC.put("requestId", "req-abc123");

        new AuditRecorder(writer, realExecutor()).record(event(null));

        assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isEqualTo("req-abc123");
    }

    @Test
    void anOverflowingQueueFallsBackToTheCallerInsteadOfDroppingEvents() throws Exception {
        // 큐 포화에서 버리지 않는다는 정책을 고정한다. 최악이 "예전처럼 느려지는 것"이어야지
        // "기록이 사라지는 것"이면 안 된다.
        ThreadPoolTaskExecutor pool = realExecutor();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();
        Set<String> threadsThatRan = ConcurrentHashMap.newKeySet();

        pool.execute(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        int submitted = 1_100; // 큐 용량(1000)보다 확실히 많게
        try {
            for (int i = 0; i < submitted; i++) {
                pool.execute(() -> {
                    executed.incrementAndGet();
                    threadsThatRan.add(Thread.currentThread().getName());
                });
            }
            // 넘친 몫은 호출 스레드가 직접 실행했어야 한다.
            assertThat(threadsThatRan).contains(Thread.currentThread().getName());
        } finally {
            release.countDown();
        }

        pool.shutdown();
        executor = null; // 위에서 이미 내렸다

        // 그리고 한 건도 버려지지 않았어야 한다.
        assertThat(executed.get()).isEqualTo(submitted);
    }
}
