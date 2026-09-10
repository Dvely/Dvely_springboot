package com.example.dvely.agent.infrastructure.retention;

import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code agent_runs} · {@code agent_run_events} 보존 스윕(#338).
 *
 * <p>두 테이블 모두 삭제 로직이 없었다. run 하나가 {@code plan_json LONGTEXT} 와 TEXT 컬럼
 * 여덟 개를 들고 있고, 이벤트는 태스크 한 건당 수십 행이 쌓인다.</p>
 *
 * <p>{@link com.example.dvely.audit.infrastructure.scheduler.AuditLogRetentionScheduler} 의
 * 형태를 따른다 — 고정 지연 주기, 프로퍼티로 받는 보존 기간, LIMIT 배치를 0 이 나올 때까지 반복.
 * 다른 점은 run 행 자체는 지우지 않고 큰 컬럼만 비운다는 것이다. 이유는
 * {@link AgentRunRetentionStore} 의 javadoc 에 있다(FK 때문에 행 삭제가 불가능하다).</p>
 */
@Slf4j
@Component
public class AgentRunRetentionScheduler {

    private static final int BATCH_SIZE = 500;

    private final AgentRunRetentionStore retentionStore;
    private final long retentionDays;

    public AgentRunRetentionScheduler(
            AgentRunRetentionStore retentionStore,
            @Value("${qeploy.agent.retention-days:90}") long retentionDays) {
        this.retentionStore = retentionStore;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${qeploy.agent.retention-sweep-interval-ms:3600000}")
    public void purgeExpiredAgentRunPayloads() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        int blanked = repeatUntilExhausted(
                batchSize -> retentionStore.blankTerminalPayloadsBatch(cutoff, batchSize));
        int deletedEvents = repeatUntilExhausted(
                batchSize -> retentionStore.deleteTerminalEventsBatch(cutoff, batchSize));

        if (blanked > 0 || deletedEvents > 0) {
            log.info("에이전트 실행 retention 정리: 페이로드 비움={}건 이벤트 삭제={}건 retentionDays={}",
                    blanked, deletedEvents, retentionDays);
        }
    }

    /** 한 배치가 0 을 돌려줄 때까지 반복하고 총합을 돌려준다(AuditLogRetentionScheduler 와 같은 형태). */
    private int repeatUntilExhausted(java.util.function.IntUnaryOperator batch) {
        int total = 0;
        int affected;
        do {
            affected = batch.applyAsInt(BATCH_SIZE);
            total += affected;
        } while (affected > 0);
        return total;
    }
}
