package com.example.dvely.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.ThinkingLevel;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmRequestOptionsTest {

    private static final int BASE_MAX_TOKENS = 8_192;

    @Test
    void leavesAnthropicsOutputAllowanceAloneWhenThinkingIsOff() {
        Map<String, Object> body = new HashMap<>();

        LlmRequestOptions.applyAnthropic(body, options(ThinkingLevel.OFF), BASE_MAX_TOKENS);

        assertThat(body).containsEntry("max_tokens", BASE_MAX_TOKENS).doesNotContainKey("thinking");
    }

    @Test
    void raisesAnthropicsMaxTokensAboveTheThinkingBudget() {
        // The budget is spent out of the same output allowance, and the API rejects a request whose
        // max_tokens does not exceed budget_tokens — adding it on top also leaves the answer itself
        // the full allowance it had before thinking was switched on.
        Map<String, Object> body = new HashMap<>();

        LlmRequestOptions.applyAnthropic(body, options(ThinkingLevel.HIGH), BASE_MAX_TOKENS);

        @SuppressWarnings("unchecked")
        Map<String, Object> thinking = (Map<String, Object>) body.get("thinking");
        assertThat(thinking).containsEntry("type", "enabled");
        int budgetTokens = (int) thinking.get("budget_tokens");
        assertThat((int) body.get("max_tokens"))
                .isGreaterThan(budgetTokens)
                .isEqualTo(BASE_MAX_TOKENS + budgetTokens);
    }

    @Test
    void spendsMoreBudgetForDeeperThinking() {
        assertThat(budgetFor(ThinkingLevel.LOW))
                .isLessThan(budgetFor(ThinkingLevel.MEDIUM));
        assertThat(budgetFor(ThinkingLevel.MEDIUM))
                .isLessThan(budgetFor(ThinkingLevel.HIGH));
        // Anthropic rejects a budget below 1024.
        assertThat(budgetFor(ThinkingLevel.LOW)).isGreaterThanOrEqualTo(1_024);
    }

    @Test
    void sendsNoReasoningEffortToOpenAiWhenThinkingIsOff() {
        Map<String, Object> body = new HashMap<>();

        LlmRequestOptions.applyOpenAi(body, options(ThinkingLevel.OFF));

        // Models like gpt-4o reject the parameter outright, so absent must mean absent.
        assertThat(body).doesNotContainKey("reasoning_effort");
    }

    @Test
    void mapsThinkingLevelsOntoOpenAiReasoningEffort() {
        assertThat(reasoningEffortFor(ThinkingLevel.LOW)).isEqualTo("low");
        assertThat(reasoningEffortFor(ThinkingLevel.MEDIUM)).isEqualTo("medium");
        assertThat(reasoningEffortFor(ThinkingLevel.HIGH)).isEqualTo("high");
    }

    @Test
    void asksOpenRouterForReasoningInItsOwnSpellingRatherThanOpenAis() {
        // OpenRouter is wire-compatible with OpenAI everywhere else, but reasoning is the one
        // parameter it defines itself. Sending reasoning_effort there risks the worst outcome
        // available: a request that is accepted and quietly thinks no harder than before.
        Map<String, Object> body = new HashMap<>();

        LlmRequestOptions.applyOpenAiCompatible(
                body, options(ThinkingLevel.HIGH), LlmRequestOptions.ReasoningStyle.OPENROUTER_REASONING);

        assertThat(body).doesNotContainKey("reasoning_effort");
        assertThat(body).containsEntry("reasoning", Map.of("effort", "high"));
    }

    @Test
    void sendsNoReasoningToOpenRouterWhenThinkingIsOff() {
        Map<String, Object> body = new HashMap<>();

        LlmRequestOptions.applyOpenAiCompatible(
                body, options(ThinkingLevel.OFF), LlmRequestOptions.ReasoningStyle.OPENROUTER_REASONING);

        assertThat(body).doesNotContainKey("reasoning");
    }

    @Test
    void asksZaiForThinkingInItsOwnSpellingWhichHasNoEffortLevels() {
        // 2026-09-03 실측: Z.ai 는 OpenRouter/OpenAI 표기를 거절하지 않고 조용히 무시한다
        // (reasoning_tokens 가 무파라미터 대역을 벗어나지 못했다). 그래서 표기를 틀리면 요청이
        // 통과하면서 사고 깊이만 사라진다 — 켜진 요청과 결과가 구분되지 않는 형태다.
        Map<String, Object> body = new HashMap<>();

        LlmRequestOptions.applyOpenAiCompatible(
                body, options(ThinkingLevel.HIGH), LlmRequestOptions.ReasoningStyle.ZAI_THINKING);

        assertThat(body).doesNotContainKey("reasoning").doesNotContainKey("reasoning_effort");
        assertThat(body).containsEntry("thinking", Map.of("type", "enabled"));
    }

    @Test
    void everyEnabledLevelLandsOnZaisSingleDepth() {
        // Z.ai 에는 단계가 없다. 거절하지 않는 이유는 거절하면 Z.ai 배포가 thinking 을 아예 못
        // 쓰게 되기 때문이고, 이 비대칭은 enum 상수와 운영 설정 주석에 적어 두었다.
        for (ThinkingLevel level : new ThinkingLevel[] {ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH}) {
            Map<String, Object> body = new HashMap<>();
            LlmRequestOptions.applyOpenAiCompatible(
                    body, options(level), LlmRequestOptions.ReasoningStyle.ZAI_THINKING);
            assertThat(body).as("level=%s", level)
                    .containsEntry("thinking", Map.of("type", "enabled"));
        }
    }

    @Test
    void thinkingOffIsSaidOutLoudToZaiBecauseItReasonsByDefault() {
        // 다른 두 게이트웨이와 다른 지점이다. 아무것도 안 쓰면 Z.ai 는 그냥 추론해버리므로,
        // thinking 을 끈 요청이 완전히 추론된 답을 받고 그 비용까지 청구된다.
        // 실측: thinking:{type:disabled} 를 보내면 reasoning_tokens 가 0, 0, 0 으로 떨어진다.
        Map<String, Object> body = new HashMap<>();

        LlmRequestOptions.applyOpenAiCompatible(
                body, options(ThinkingLevel.OFF), LlmRequestOptions.ReasoningStyle.ZAI_THINKING);

        assertThat(body).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void thinkingOffStaysUnsaidForTheGatewaysThatRejectOrIgnoreIt() {
        for (LlmRequestOptions.ReasoningStyle style : new LlmRequestOptions.ReasoningStyle[] {
                LlmRequestOptions.ReasoningStyle.REASONING_EFFORT,
                LlmRequestOptions.ReasoningStyle.OPENROUTER_REASONING}) {
            Map<String, Object> body = new HashMap<>();
            LlmRequestOptions.applyOpenAiCompatible(body, options(ThinkingLevel.OFF), style);
            assertThat(body).as("style=%s", style).isEmpty();
        }
    }

    private int budgetFor(ThinkingLevel level) {
        Map<String, Object> body = new HashMap<>();
        LlmRequestOptions.applyAnthropic(body, options(level), BASE_MAX_TOKENS);
        @SuppressWarnings("unchecked")
        Map<String, Object> thinking = (Map<String, Object>) body.get("thinking");
        return (int) thinking.get("budget_tokens");
    }

    private String reasoningEffortFor(ThinkingLevel level) {
        Map<String, Object> body = new HashMap<>();
        LlmRequestOptions.applyOpenAi(body, options(level));
        return (String) body.get("reasoning_effort");
    }

    private AiModelOptions options(ThinkingLevel thinking) {
        return new AiModelOptions("model-id", thinking);
    }
}
