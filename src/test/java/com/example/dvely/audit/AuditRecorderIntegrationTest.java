package com.example.dvely.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.model.AuditLog;
import com.example.dvely.audit.domain.repository.AuditLogRepository;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
import com.example.dvely.audit.infrastructure.config.AuditLogExecutor;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Design ad-audit-log-design.md §5.1/§11 — "이 단위의 심장": proves {@code AuditLogWriter}'s
 * {@code REQUIRES_NEW} actually behaves the way ADR-A2 depends on against a real MySQL schema, not
 * just against Mockito's transaction-unaware test doubles. Mirrors
 * {@code ProjectOptimisticLockIntegrationTest}'s manual {@link TransactionTemplate} technique
 * (no threads needed) to drive a real outer transaction around the call.
 */
@SpringBootTest
class AuditRecorderIntegrationTest {

    @Autowired
    private AuditRecorder auditRecorder;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AuditLogExecutor auditLogExecutor;

    @Test
    void auditRowSurvivesOuterTransactionRollback() {
        // A unique projectId per test run so this test's row is unambiguously identifiable among
        // whatever else this shared local MySQL schema may already contain.
        long projectId = uniqueProjectId();
        AuditEvent event = event(projectId, "rollback-marker");
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        outerTransaction.execute(status -> {
            auditRecorder.record(event);
            // The audited business action (e.g. a GitHub repository deletion, design H3) failed
            // for some unrelated reason after the audit call — its own transaction must roll back,
            // but the audit record of the already-happened external effect must not disappear
            // with it (design ADR-A2's core claim).
            status.setRollbackOnly();
            return null;
        });

        assertThat(awaitSingleRow(projectId).getResourceId()).isEqualTo("rollback-marker");
    }

    @Test
    void recordSucceedsWithNoAmbientTransaction() {
        // This test method itself is not @Transactional — mirrors the async/no-tx hook contexts
        // (design F6/F8: DeployAgentService, InfraOpsAgentService, the deployment worker) where
        // AuditLogWriter's REQUIRES_NEW is "just a short standalone transaction" rather than a
        // nested one (design §5.1).
        long projectId = uniqueProjectId();
        AuditEvent event = event(projectId, "no-tx-marker");

        assertThatCode(() -> auditRecorder.record(event)).doesNotThrowAnyException();

        assertThat(awaitSingleRow(projectId).getResourceId()).isEqualTo("no-tx-marker");
    }

    @Test
    void recordNeverThrowsEvenWhenTheUnderlyingWriteFails() {
        // A null action fails AuditLog.from()'s Objects.requireNonNull *inside* AuditLogWriter,
        // simulating "the write itself blew up for some reason" (design §5.1's contract: the
        // caller's own operation must never fail because of this). record() must swallow it (only
        // the AUDIT_FALLBACK log line is the observable trace) rather than propagate.
        AuditEvent brokenEvent = new AuditEvent(
                null, AuditOutcome.SUCCEEDED, AuditActorType.USER, 1L, uniqueProjectId(),
                null, "should-not-be-persisted", null, null, null, null
        );

        assertThatCode(() -> auditRecorder.record(brokenEvent)).doesNotThrowAnyException();
    }

    /**
     * 감사 스레드가 자기 트랜잭션으로 커밋하므로 {@code record()} 가 돌아온 시점에는 행이 아직 안 보일
     * 수 있다. 큐가 빈 것을 확정한 뒤 읽는다 - 읽고 나서 없으면 그건 지연이 아니라 진짜 유실이다.
     */
    private AuditLog awaitSingleRow(long projectId) {
        assertThat(auditLogExecutor.awaitDrained(Duration.ofSeconds(10)))
                .withFailMessage("감사 큐가 비지 않았습니다 (projectId=%d)", projectId)
                .isTrue();

        List<AuditLog> found = auditLogRepository.findByProjectIdOrderByIdDesc(projectId, 10);
        assertThat(found)
                .withFailMessage("감사 행이 남지 않았습니다 (projectId=%d)", projectId)
                .hasSize(1);
        return found.get(0);
    }

    private AuditEvent event(long projectId, String resourceId) {
        return new AuditEvent(
                AuditAction.DEPLOYMENT_REQUESTED,
                AuditOutcome.SUCCEEDED,
                AuditActorType.USER,
                1L,
                projectId,
                "DEPLOYMENT",
                resourceId,
                null,
                null,
                "target=LATEST",
                null
        );
    }

    private long uniqueProjectId() {
        // Deliberately not a real projects.project_id — audit_logs has no FK (ADR-A3), so any
        // BIGINT value is a valid, storable projectId for this table's own purposes. Using
        // System.nanoTime() keeps concurrent test runs against the same shared local DB from
        // colliding on the same rows.
        return System.nanoTime();
    }
}
