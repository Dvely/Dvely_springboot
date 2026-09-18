package com.example.dvely.audit.application;

import com.example.dvely.common.security.SecretRedactor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Public entry point every hook (design §4 H1~H14) calls: {@code auditRecorder.record(event)}.
 * This is the entire non-blocking contract of the feature (design ADR-A2) — no exception thrown
 * by the audit write path is ever allowed to propagate out of {@link #record}, because the
 * business operation the hook is attached to (a GitHub push, a deployment state transition, ...)
 * must never fail, retry, or roll back because audit logging had a problem.
 *
 * <p>U10 이후 실제 INSERT 는 전용 스레드({@code auditLogExecutor})에서 일어난다. 요청 스레드는 이벤트를
 * 큐에 넣고 바로 돌아간다.</p>
 *
 * <p><b>커밋 후로 미루지 않고 즉시 큐에 넣는 이유.</b> {@link AuditLogWriter} 의 {@code REQUIRES_NEW}
 * 는 "감사 실패가 요청을 죽이지 않는다"만이 아니라 <b>"바깥 트랜잭션이 되감겨도 감사 기록은 남는다"</b>
 * 도 함께 뜻한다(design ADR-A2). 이건 실수가 아니라 필요다 —
 * {@code RepositoryProvisioningService} 처럼 <b>이미 일어난 외부 효과</b>(GitHub 저장소 생성)를
 * 기록하는 훅이 있고, 바깥 트랜잭션이 되감겨도 그 저장소는 GitHub 에 그대로 남는다. 여기서
 * {@code afterCommit} 으로 미루면 되돌릴 수 없는 외부 효과의 유일한 증거가 사라진다. 그래서 비동기로
 * 바꾸면서도 "언제 쓰는가"의 의미는 건드리지 않았다.</p>
 *
 * <p>바뀐 것이 하나 있다: 프로세스가 갑자기 죽으면 큐에 남은 이벤트가 사라진다. 정상 종료에서는
 * 실행기가 큐를 흘려보내고 내려가며(design §5.3 이 이미 받아들인 "유실 창"의 연장), 그 대가로 쓰기
 * 요청마다 순간적으로 커넥션을 하나 더 빌리던 결합이 사라진다.</p>
 */
@Slf4j
@Component
public class AuditRecorder {

    private final AuditLogWriter writer;
    private final Executor auditLogExecutor;

    public AuditRecorder(AuditLogWriter writer, @Qualifier("auditLogExecutor") Executor auditLogExecutor) {
        this.writer = writer;
        this.auditLogExecutor = auditLogExecutor;
    }

    /** Records one audit event. Never throws — see class javadoc. */
    public void record(AuditEvent event) {
        // 요청 ID 를 같이 넘긴다. 감사 스레드에서 남는 로그(특히 아래 AUDIT_FALLBACK)가 어느 요청의
        // 것인지 알 수 없으면, 정작 추적이 필요할 때 추적이 안 된다.
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        try {
            auditLogExecutor.execute(() -> writeSwallowingEveryFailure(event, callerContext));
        } catch (Throwable submissionFailure) {
            // 큐에 넣는 것 자체가 실패했으면(컨텍스트 종료 등) 이 스레드에서 마지막으로 시도한다.
            // 여기서도 예외는 아래에서 삼켜지므로 호출자에게는 아무것도 전파되지 않는다.
            writeSwallowingEveryFailure(event, callerContext);
        }
    }

    private void writeSwallowingEveryFailure(AuditEvent event, Map<String, String> callerContext) {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        if (callerContext != null) {
            MDC.setContextMap(callerContext);
        }
        try {
            writer.write(event);
        } catch (Exception exception) {
            // Fallback trail: if this line itself is ever lost (e.g. log shipping outage), the
            // audit event is genuinely gone — there is no second fallback beneath this one (design
            // §5.3 "유실 창", accepted). The fixed "AUDIT_FALLBACK" prefix exists so operators can
            // alert on this exact string; its appearance at all is the signal, since audit writes
            // are expected to succeed essentially always.
            //
            // Review follow-up (Medium-1, ad-audit-review.md): errorSummary was missing from this
            // format string entirely — for a FAILED-outcome event (H9/H8/H12), that field is
            // exactly the failure reason this fallback line exists to preserve. Unlike the write
            // path in AuditLog#from (which redacts before persisting to audit_logs), event.errorSummary()
            // here is still the caller's raw, un-redacted value — logging it as-is would open a
            // new secret-leak path into the application log that this feature's own §7 policy
            // exists to close. Redact it the same way (SecretRedactor.redact) before it ever
            // reaches this log line.
            log.error("AUDIT_FALLBACK action={} outcome={} actorType={} actorUserId={} projectId={} "
                            + "resourceType={} resourceId={} taskId={} approvalId={} detail={} errorSummary={}",
                    event.action(), event.outcome(), event.actorType(), event.actorUserId(),
                    event.projectId(), event.resourceType(), event.resourceId(),
                    event.taskId(), event.approvalId(), event.detail(),
                    SecretRedactor.redact(event.errorSummary()), exception);
        } finally {
            // 감사 스레드는 다음 이벤트를 위해 남아 있는다. 지우지 않으면 다음 이벤트의 로그에 남의
            // 요청 ID 가 붙는다.
            if (previousContext == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousContext);
            }
        }
    }
}
