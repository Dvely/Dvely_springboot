package com.example.dvely.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.ThinkingLevel;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmRequestOptionsTest {

    private static final int BASE_MAX_TOKENS = 8_192;
    /** budget_tokens 만 받는 모델(운영 기본값). 아래 extended 단언은 이 모델 기준이다. */
    private static final String EXTENDED_MODEL = "claude-opus-4-5-20251101";

    // ── #510: 모델별 thinking 형식 ────────────────────────────────────────────────

    @Test
    void readsTheThinkingFormFromTheModelIdAsAnthropicsTableDoes() {
        // 2026-10-28 공식 표: 4.6 이상은 adaptive(4.6 은 budget_tokens 도 받지만 폐기 예정), 4.7 이상은 budget_tokens 가 400,
        // 4.5 이하는 adaptive 가 400.
        for (String adaptive : java.util.List.of("claude-opus-4-6", "claude-sonnet-4-6", "claude-opus-4-7", "claude-opus-4-8",
                "claude-opus-5", "claude-opus-5-5", "claude-sonnet-5", "claude-sonnet-5-5", "claude-haiku-5-5",
                "claude-fable-5", "claude-fable-5-1", "claude-mythos-5-1", "claude-mythos-preview",
                "us.anthropic.claude-opus-4-6-v1:0", "claude-sonnet-4-6@20260101")) {
            assertThat(LlmRequestOptions.usesAdaptiveThinking(adaptive)).as(adaptive).isTrue();
        }
        for (String extended : java.util.List.of("claude-opus-4-5-20251101", "claude-opus-4-5", "claude-sonnet-4-5-20250929",
                "claude-haiku-4-5", "claude-haiku-4-5-20251001", "claude-opus-4-1-20250805", "claude-sonnet-4-20250514",
                "claude-opus-4-20250514", "claude-3-7-sonnet-20250219", "claude-3-5-haiku-20241022",
                "anthropic.claude-sonnet-4-5-20250929-v1:0", "claude-opus-4-5@20251101")) {
            assertThat(LlmRequestOptions.usesAdaptiveThinking(extended)).as(extended).isFalse();
        }
    }

    @Test
    void newerModelsGetAdaptiveThinkingWithEffortInsteadOfABudget() {
        Map<String, Object> body = new HashMap<>();

        LlmRequestOptions.applyAnthropic(body, "claude-opus-5-5", options(ThinkingLevel.HIGH), BASE_MAX_TOKENS);

        assertThat(body).containsEntry("thinking", Map.of("type", "adaptive"))
                .containsEntry("output_config", Map.of("effort", "high"));
        // 4.7 이상은 budget_tokens 가 들어가면 400 이다
        assertThat(body.toString()).doesNotContain("budget_tokens").doesNotContain("enabled");
        // 생각도 max_tokens 에서 쓰므로 같은 여유를 준다
        assertThat((int) body.get("max_tokens")).isGreaterThan(BASE_MAX_TOKENS);
    }

    @Test
    void thinkingLevelsMapOntoEffortLevels() {
        for (var entry : Map.of(ThinkingLevel.LOW, "low", ThinkingLevel.MEDIUM, "medium", ThinkingLevel.HIGH, "high").entrySet()) {
            Map<String, Object> body = new HashMap<>();
            LlmRequestOptions.applyAnthropic(body, "claude-sonnet-4-6", options(entry.getKey()), BASE_MAX_TOKENS);
            assertThat(body).as("level=%s", entry.getKey()).containsEntry("output_config", Map.of("effort", entry.getValue()));
        }
    }

    @Test
    void olderModelsKeepTheBudgetFormBecauseAdaptiveIsRejectedThere() {
        Map<String, Object> body = new HashMap<>();

        LlmRequestOptions.applyAnthropic(body, "claude-haiku-4-5", options(ThinkingLevel.LOW), BASE_MAX_TOKENS);

        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "enabled", "budget_tokens", 2_048));
        assertThat(body).doesNotContainKey("output_config");
    }

    @Test
    void thinkingOffSendsNoThinkingOnAnyModel() {
        // 일부 새 모델은 "disabled" 를 400 으로 거부한다 — 보내지 않는 것만이 모든 모델이 받는 형태다
        for (String model : java.util.List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-opus-4-5-20251101")) {
            Map<String, Object> body = new HashMap<>();
            LlmRequestOptions.applyAnthropic(body, model, options(ThinkingLevel.OFF), BASE_MAX_TOKENS);
            assertThat(body).as(model).containsOnlyKeys("max_tokens");
        }
    }

    @Test
    void leavesAnthropicsOutputAllowanceAloneWhenThinkingIsOff() {
        Map<String, Object> body = new HashMap<>();

        LlmRequestOptions.applyAnthropic(body, EXTENDED_MODEL, options(ThinkingLevel.OFF), BASE_MAX_TOKENS);

        assertThat(body).containsEntry("max_tokens", BASE_MAX_TOKENS).doesNotContainKey("thinking");
    }

    @Test
    void raisesAnthropicsMaxTokensAboveTheThinkingBudget() {
        // The budget is spent out of the same output allowance, and the API rejects a request whose
        // max_tokens does not exceed budget_tokens — adding it on top also leaves the answer itself
        // the full allowance it had before thinking was switched on.
        Map<String, Object> body = new HashMap<>();

        LlmRequestOptions.applyAnthropic(body, EXTENDED_MODEL, options(ThinkingLevel.HIGH), BASE_MAX_TOKENS);

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
        LlmRequestOptions.applyAnthropic(body, EXTENDED_MODEL, options(level), BASE_MAX_TOKENS);
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
