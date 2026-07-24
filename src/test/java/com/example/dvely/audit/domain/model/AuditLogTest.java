package com.example.dvely.audit.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditCategory;
import com.example.dvely.audit.domain.value.AuditOutcome;
import org.junit.jupiter.api.Test;

/** Design §11 — construction-time guarantees {@code AuditLogWriter} relies on (§5.1/§7). */
class AuditLogTest {

    @Test
    void fromDerivesCategoryFromAction() {
        AuditLog auditLog = AuditLog.from(event(AuditAction.DOMAIN_BOUND, null, null));

        assertThat(auditLog.getCategory()).isEqualTo(AuditCategory.DOMAIN);
    }

    @Test
    void fromTruncatesDetailTo1000Characters() {
        String longDetail = "x".repeat(1500);

        AuditLog auditLog = AuditLog.from(event(AuditAction.DEPLOYMENT_REQUESTED, longDetail, null));

        assertThat(auditLog.getDetail()).hasSize(1000);
        assertThat(auditLog.getDetail()).isEqualTo("x".repeat(1000));
    }

    @Test
    void fromTruncatesErrorSummaryTo500CharactersAfterRedaction() {
        String longErrorSummary = "y".repeat(700);

        AuditLog auditLog = AuditLog.from(event(AuditAction.DEPLOYMENT_FAILED, null, longErrorSummary));

        assertThat(auditLog.getErrorSummary()).hasSize(500);
    }

    @Test
    void fromRedactsSecretShapedErrorSummaryBeforeTruncation() {
        String errorSummary = "auth failed token=ghp_1234567890abcdefghijklmno";

        AuditLog auditLog = AuditLog.from(event(AuditAction.DEPLOYMENT_FAILED, null, errorSummary));

        assertThat(auditLog.getErrorSummary())
                .doesNotContain("ghp_1234567890abcdefghijklmno")
                .contains("***REDACTED***");
    }

    @Test
    void fromKeepsNullDetailAndErrorSummaryAsNull() {
        AuditLog auditLog = AuditLog.from(event(AuditAction.DEPLOYMENT_REQUESTED, null, null));

        assertThat(auditLog.getDetail()).isNull();
        assertThat(auditLog.getErrorSummary()).isNull();
    }

    @Test
    void fromRejectsNullAction() {
        AuditEvent eventWithNullAction = new AuditEvent(
                null, AuditOutcome.SUCCEEDED, AuditActorType.USER, 1L, 1L, null, null, null, null, null, null);

        // action is read via AuditAction#category() inside from(); a null action must fail loudly
        // here rather than persist a row with an unresolved category.
        assertThatThrownBy(() -> AuditLog.from(eventWithNullAction))
                .isInstanceOf(NullPointerException.class);
    }

    // Review follow-up (Low-3, ad-audit-review.md): the underlying constructor is now private —
    // AuditLog.restore(...) (used by AuditLogEntity#toDomain to rehydrate a persisted row) is the
    // only way to reach it from outside this class, so these guard checks are exercised through
    // that factory instead of a constructor call.

    @Test
    void restoreRejectsNullAction() {
        assertThatThrownBy(() -> AuditLog.restore(
                null, null, AuditOutcome.SUCCEEDED, AuditActorType.USER, 1L, 1L,
                null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("action");
    }

    @Test
    void restoreRejectsNullOutcome() {
        assertThatThrownBy(() -> AuditLog.restore(
                null, AuditAction.DEPLOYMENT_REQUESTED, null, AuditActorType.USER, 1L, 1L,
                null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outcome");
    }

    @Test
    void restoreRejectsNullActorType() {
        assertThatThrownBy(() -> AuditLog.restore(
                null, AuditAction.DEPLOYMENT_REQUESTED, AuditOutcome.SUCCEEDED, null, 1L, 1L,
                null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorType");
    }

    private AuditEvent event(AuditAction action, String detail, String errorSummary) {
        return new AuditEvent(
                action, AuditOutcome.SUCCEEDED, AuditActorType.USER, 1L, 1L,
                "PROJECT", "1", null, null, detail, errorSummary
        );
    }
}
