package com.example.dvely.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Review follow-up (Medium-1, ad-audit-review.md): pins the {@code AUDIT_FALLBACK} log line's
 * actual content with a real Logback {@link ListAppender} capture (mirrors the reviewer's own
 * reproduction technique, §2 of the review) — a plain {@code verify(log)} call cannot catch a
 * missing format-string placeholder the way asserting on the rendered message text can.
 */
class AuditRecorderTest {

    @Test
    void fallbackLogIncludesRedactedErrorSummaryWithoutLeakingTheRawSecret() {
        AuditLogWriter writer = mock(AuditLogWriter.class);
        doThrow(new RuntimeException("write failed")).when(writer).write(any());
        AuditRecorder recorder = new AuditRecorder(writer);

        Logger logbackLogger = (Logger) LoggerFactory.getLogger(AuditRecorder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            // A FAILED-outcome event with a secret-shaped errorSummary — exactly the H9/H8/H12
            // shape the review flagged: this field is the one piece of information the fallback
            // log exists to preserve when the real audit write itself fails.
            AuditEvent event = new AuditEvent(
                    com.example.dvely.audit.domain.value.AuditAction.DEPLOYMENT_FAILED,
                    com.example.dvely.audit.domain.value.AuditOutcome.FAILED,
                    com.example.dvely.audit.domain.value.AuditActorType.SYSTEM,
                    1L, 2L, "DEPLOYMENT", "500", null, null, "some-detail-value",
                    "deployment failed: token=" + "ghp_shouldnotleak1234567890" + " rejected"
            );

            recorder.record(event);

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
}
