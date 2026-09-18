package com.example.dvely.agent.infrastructure.usage;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.domain.value.LlmUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 제공자 클라이언트가 호출 한 건을 끝낼 때마다 여기로 사용량을 넘긴다.
 *
 * <p>두 가지를 한다. (1) 행 하나를 남겨 같은 시나리오의 전/후 토큰 합계를 비교할 수 있게 하고,
 * (2) 열려 있는 {@link LlmUsageScope} 에 누적해 태스크당 예산 상한을 건다.</p>
 *
 * <p>기록 실패는 {@link LlmUsageStore} 가 삼킨다. 반대로 예산 초과는 삼키지 않고 그대로 올린다 —
 * 그것이 이 기능의 목적이다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmUsageRecorder {

    private final LlmUsageStore store;

    /**
     * 에이전트 태스크 하나의 실행 구간을 연다.
     *
     * <p>이전 실행이 쓴 양을 읽어 이어서 센다 — 그래야 상한이 태스크 재시도를 건너 유효하다.
     * 상한이 꺼져 있으면(0) 조회도 하지 않는다.</p>
     */
    public LlmUsageScope openTaskScope(String taskId, Long userId, Long projectId, long budgetTokens) {
        long alreadyUsed = budgetTokens > 0 ? store.tokensAlreadyUsedBy(taskId) : 0;
        return LlmUsageScope.open(
                taskId, userId, projectId, LlmUsagePhase.AGENT_RUN, budgetTokens, alreadyUsed);
    }

    public void record(AiProvider provider, String model, LlmUsage usage) {
        if (usage == null || usage.isEmpty()) {
            // 응답에 usage 가 없었다는 뜻이다. 0 행을 쌓아 합계를 희석시키느니 남기지 않는다.
            return;
        }
        LlmUsageScope scope = LlmUsageScope.current();
        store.save(
                scope == null ? null : scope.taskId(),
                scope == null ? null : scope.userId(),
                scope == null ? null : scope.projectId(),
                scope == null ? LlmUsagePhase.UNSCOPED : scope.phase(),
                provider.name(),
                model == null || model.isBlank() ? "unknown" : model,
                usage
        );
        if (scope != null) {
            // save 다음이다 — 상한을 터뜨린 호출도 이미 과금됐으므로 기록에는 남아야 한다.
            scope.accumulate(usage);
        }
    }
}
