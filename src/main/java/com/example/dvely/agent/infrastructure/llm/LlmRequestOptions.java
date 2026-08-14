package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.ThinkingLevel;
import java.util.Map;

/**
 * Writes a {@link AiModelOptions} into each provider's request body.
 *
 * <p>The two providers spell the same idea differently — Anthropic takes a token budget for
 * extended thinking, OpenAI takes a {@code reasoning_effort} string — and Anthropic additionally
 * requires {@code max_tokens} to exceed the thinking budget, since the budget is spent out of the
 * same output allowance. Getting that wrong is a 400 from the API, so the arithmetic lives here
 * rather than in each client.</p>
 */
final class LlmRequestOptions {

    // Anthropic requires at least 1024; these are the levels the API is asked for, and the answer
    // still stops when the model is done — the budget is a ceiling, not a quota to be spent.
    private static final int LOW_BUDGET_TOKENS = 2_048;
    private static final int MEDIUM_BUDGET_TOKENS = 4_096;
    private static final int HIGH_BUDGET_TOKENS = 8_192;

    private LlmRequestOptions() {
    }

    /**
     * Sets Anthropic's {@code max_tokens}, and {@code thinking} when asked for.
     *
     * @param baseMaxTokens the client's normal output allowance, which stays exactly that when
     *                      thinking is off; with thinking on, the budget is added on top so the
     *                      answer itself still has the full allowance available
     */
    static void applyAnthropic(Map<String, Object> body, AiModelOptions options, int baseMaxTokens) {
        if (!options.thinking().isEnabled()) {
            body.put("max_tokens", baseMaxTokens);
            return;
        }
        int budgetTokens = budgetTokens(options.thinking());
        body.put("thinking", Map.of("type", "enabled", "budget_tokens", budgetTokens));
        body.put("max_tokens", baseMaxTokens + budgetTokens);
    }

    /** Sets OpenAI's {@code reasoning_effort}, or leaves the body untouched when thinking is off. */
    static void applyOpenAi(Map<String, Object> body, AiModelOptions options) {
        if (!options.thinking().isEnabled()) {
            return;
        }
        body.put("reasoning_effort", switch (options.thinking()) {
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case OFF -> throw new IllegalStateException("도달할 수 없음: thinking OFF");
        });
    }

    private static int budgetTokens(ThinkingLevel thinking) {
        return switch (thinking) {
            case LOW -> LOW_BUDGET_TOKENS;
            case MEDIUM -> MEDIUM_BUDGET_TOKENS;
            case HIGH -> HIGH_BUDGET_TOKENS;
            case OFF -> 0;
        };
    }
}
