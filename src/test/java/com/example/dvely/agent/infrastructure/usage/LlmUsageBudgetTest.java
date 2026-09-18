package com.example.dvely.agent.infrastructure.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.exception.AgentTokenBudgetExceededException;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.domain.value.LlmUsage;
import org.junit.jupiter.api.Test;

class LlmUsageBudgetTest {

    private final LlmUsageStore store = mock(LlmUsageStore.class);
    private final LlmUsageRecorder recorder = new LlmUsageRecorder(store);

    @Test
    void stopsTheTaskOnceItsCumulativeTokensPassTheBudget() {
        try (LlmUsageScope scope = recorder.openTaskScope("task-1", 1L, 11L, 1_000)) {
            recorder.record(AiProvider.GLM, "z-ai/glm-4.6", new LlmUsage(400, 100, 0, 0));
            assertThat(scope.usedTokens()).isEqualTo(500);

            assertThatThrownBy(() ->
                    recorder.record(AiProvider.GLM, "z-ai/glm-4.6", new LlmUsage(600, 50, 0, 0)))
                    .isInstanceOf(AgentTokenBudgetExceededException.class)
                    .hasMessageContaining("AI 토큰 예산 상한");
        }
    }

    @Test
    void stillRecordsTheCallThatBustTheBudget() {
        // 상한을 터뜨린 호출도 이미 과금됐다. 기록에서 빠지면 사용자에게 보이는 수치가 실제
        // 청구와 어긋난다.
        try (LlmUsageScope ignored = recorder.openTaskScope("task-1", 1L, 11L, 100)) {
            assertThatThrownBy(() ->
                    recorder.record(AiProvider.ANTHROPIC, "claude", new LlmUsage(500, 0, 0, 0)))
                    .isInstanceOf(AgentTokenBudgetExceededException.class);
        }

        verify(store).save(eq("task-1"), eq(1L), eq(11L), eq(LlmUsagePhase.AGENT_RUN),
                eq("ANTHROPIC"), eq("claude"), any(LlmUsage.class));
    }

    @Test
    void carriesThePreviousAttemptsTokensSoARetryCannotResetTheBudget() {
        // 실행마다 0 에서 다시 세면 상한이 태스크 재시도 횟수만큼 곱해진다 — 닫으려던 곱셈이
        // 그대로 남는다.
        when(store.tokensAlreadyUsedBy("task-1")).thenReturn(990L);

        try (LlmUsageScope scope = recorder.openTaskScope("task-1", 1L, 11L, 1_000)) {
            assertThat(scope.usedTokens()).isEqualTo(990);
            assertThatThrownBy(() ->
                    recorder.record(AiProvider.GLM, "glm", new LlmUsage(20, 0, 0, 0)))
                    .isInstanceOf(AgentTokenBudgetExceededException.class);
        }
    }

    @Test
    void doesNotQueryPreviousUsageWhenTheBudgetIsDisabled() {
        try (LlmUsageScope scope = recorder.openTaskScope("task-1", 1L, 11L, 0)) {
            recorder.record(AiProvider.GLM, "glm", new LlmUsage(9_999_999, 0, 0, 0));
            assertThat(scope.usedTokens()).isEqualTo(9_999_999);
        }

        verify(store, never()).tokensAlreadyUsedBy(anyString());
    }

    @Test
    void attributesCallsMadeWithNoOpenScopeRatherThanDroppingThem() {
        // 귀속이 없어도 합계에서 사라지면 안 된다 — "합계가 맞는가" 를 의심하게 만드는 순간
        // 계측 전체가 쓸모없어진다.
        recorder.record(AiProvider.OPENAI, "gpt-4o", new LlmUsage(10, 5, 0, 0));

        verify(store).save(eq(null), eq(null), eq(null), eq(LlmUsagePhase.UNSCOPED),
                eq("OPENAI"), eq("gpt-4o"), any(LlmUsage.class));
    }

    @Test
    void recordsNothingWhenTheProviderReturnedNoUsage() {
        recorder.record(AiProvider.OPENAI, "gpt-4o", LlmUsage.NONE);

        verify(store, never()).save(any(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void restoresThePreviousScopeOnClose() {
        try (LlmUsageScope outer = LlmUsageScope.open("outer", 1L, 11L, LlmUsagePhase.DECISION)) {
            try (LlmUsageScope inner =
                         LlmUsageScope.open("inner", 1L, 11L, LlmUsagePhase.AGENT_RUN)) {
                assertThat(LlmUsageScope.current()).isSameAs(inner);
            }
            assertThat(LlmUsageScope.current()).isSameAs(outer);
        }
        assertThat(LlmUsageScope.current()).isNull();
    }

    @Test
    void neverLeavesAScopeBehindForTheNextTaskOnTheSameThread() {
        // 스레드풀이 스레드를 재사용하므로, 닫히지 않은 스코프는 남의 작업 토큰을 엉뚱한 태스크에
        // 붙인다.
        try (LlmUsageScope ignored = recorder.openTaskScope("task-1", 1L, 11L, 10_000)) {
            assertThat(LlmUsageScope.current()).isNotNull();
        }
        assertThat(LlmUsageScope.current()).isNull();
    }
}
