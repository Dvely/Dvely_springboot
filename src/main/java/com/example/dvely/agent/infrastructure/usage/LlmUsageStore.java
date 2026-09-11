package com.example.dvely.agent.infrastructure.usage;

import com.example.dvely.agent.domain.value.LlmUsage;
import com.example.dvely.agent.infrastructure.persistence.entity.LlmUsageEntity;
import com.example.dvely.agent.infrastructure.persistence.repository.SpringDataLlmUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용량 행 하나를 쓰는 것만 하는 별도 빈.
 *
 * <p>{@link LlmUsageRecorder} 안의 private 메서드가 아니라 빈으로 분리한 이유는 하나다 —
 * 자기 호출(self-invocation)은 프록시를 타지 않아 {@code REQUIRES_NEW} 가 걸리지 않는다.
 * 별도 빈이라야 호출자의 트랜잭션과 실제로 끊긴다.</p>
 *
 * <p>끊어야 하는 이유는 양방향이다. 계측 INSERT 실패가 진행 중인 작업을 되돌려서는 안 되고,
 * 반대로 작업이 롤백되더라도 이미 쓴 토큰은 실제로 쓴 것이라 기록에 남아야 한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmUsageStore {

    private final SpringDataLlmUsageRepository repository;

    /** 이 태스크가 이전 실행까지 이미 쓴 토큰. 조회에 실패하면 0 — 계측이 작업을 막지 않는다. */
    @Transactional(readOnly = true)
    public long tokensAlreadyUsedBy(String taskId) {
        if (taskId == null) {
            return 0;
        }
        try {
            return repository.sumTotalTokensByTaskId(taskId);
        } catch (RuntimeException exception) {
            log.warn("[LlmUsage] 누적 토큰 조회 실패 — 이번 실행은 0 에서 셉니다. taskId={} exceptionType={}",
                    taskId, exception.getClass().getSimpleName());
            return 0;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(String taskId,
                     Long userId,
                     Long projectId,
                     LlmUsagePhase phase,
                     String provider,
                     String model,
                     LlmUsage usage) {
        try {
            repository.save(new LlmUsageEntity(taskId, userId, projectId, phase, provider, model, usage));
        } catch (RuntimeException exception) {
            // 계측이 태스크를 죽이는 일은 없어야 한다. 예외 타입만 남긴다 — 제공자 응답 본문이
            // 섞여 들어올 여지를 두지 않는다.
            log.warn("[LlmUsage] 사용량 기록 실패 — 작업은 그대로 진행합니다. provider={} exceptionType={}",
                    provider, exception.getClass().getSimpleName());
        }
    }
}
