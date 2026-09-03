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
