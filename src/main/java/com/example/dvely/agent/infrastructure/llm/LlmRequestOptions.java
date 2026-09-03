package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.ThinkingLevel;
import java.util.Map;

/**
 * Writes a {@link AiModelOptions} into each provider's request body.
 *
 * <p>The providers spell the same idea differently — Anthropic takes a token budget for extended
 * thinking, OpenAI takes a {@code reasoning_effort} string, OpenRouter takes a {@code reasoning}
 * object — and Anthropic additionally requires {@code max_tokens} to exceed the thinking budget,
 * since the budget is spent out of the same output allowance. Getting that wrong is a 400 from the
 * API, so the arithmetic lives here rather than in each client.</p>
 */
final class LlmRequestOptions {

    // Anthropic requires at least 1024; these are the levels the API is asked for, and the answer
    // still stops when the model is done — the budget is a ceiling, not a quota to be spent.
    private static final int LOW_BUDGET_TOKENS = 2_048;
    private static final int MEDIUM_BUDGET_TOKENS = 4_096;
    private static final int HIGH_BUDGET_TOKENS = 8_192;

    /**
     * How a provider that speaks the OpenAI chat-completions format is asked for reasoning depth.
     *
     * <p>OpenRouter is wire-compatible with OpenAI for everything else, but reasoning is the
     * parameter it defines itself, as a {@code reasoning} object. Sending OpenAI's spelling to it
     * risks the worst outcome available here — a request that is accepted and quietly thinks no
     * harder than before, indistinguishable from one that honoured the setting.</p>
     */
    enum ReasoningStyle {
        /** OpenAI: {@code "reasoning_effort": "high"}. */
        REASONING_EFFORT,
        /** OpenRouter: {@code "reasoning": {"effort": "high"}}. */
        OPENROUTER_REASONING,
        /**
         * Z.ai: {@code "thinking": {"type": "enabled"}} — on or off, with no effort levels.
         *
         * <p>Two things make this one different from the other two. It has no gradations, so LOW,
         * MEDIUM and HIGH all mean the same "on"; and Z.ai thinks <em>by default</em>, so "off" has
         * to be said out loud rather than left unsaid.</p>
         *
         * <p>2026-09-03 실측 (glm-4.7-flash, reasoning_tokens): {@code disabled} → 0, 0, 0;
         * {@code enabled} → 540, 411, 298; 파라미터 없음 → 636, 397, 465. OpenRouter 표기
         * ({@code reasoning:{effort:high}}) 와 OpenAI 표기 ({@code reasoning_effort}) 는 무파라미터
         * 대역을 벗어나지 못했고 오류도 나지 않았다 — 즉 Z.ai 는 그 둘을 조용히 무시한다.</p>
         */
        ZAI_THINKING
    }

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
        applyOpenAiCompatible(body, options, ReasoningStyle.REASONING_EFFORT);
    }

    /**
     * Sets the reasoning parameter in whichever spelling {@code style} names.
     *
     * <p>Thinking-off is not simply "write nothing". For OpenAI and OpenRouter it is — models like
     * gpt-4o reject the parameter outright, so absent has to mean absent. Z.ai reasons by default
     * though, so leaving the body untouched there gives a request that asked for no thinking a
     * fully reasoned answer, and bills for it.</p>
     */
    static void applyOpenAiCompatible(Map<String, Object> body,
                                      AiModelOptions options,
                                      ReasoningStyle style) {
        if (!options.thinking().isEnabled()) {
            if (style == ReasoningStyle.ZAI_THINKING) {
                body.put("thinking", Map.of("type", "disabled"));
            }
            return;
        }
        // Z.ai offers no gradations, so the level only decides on-or-off there. Deliberately not
        // rejected: refusing LOW/MEDIUM/HIGH would leave Z.ai deployments unable to think at all,
        // which is worse than a level that lands on the one depth the gateway has. The asymmetry
        // is documented on the enum constant and in the operator config.
        if (style == ReasoningStyle.ZAI_THINKING) {
            body.put("thinking", Map.of("type", "enabled"));
            return;
        }
        String effort = switch (options.thinking()) {
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case OFF -> throw new IllegalStateException("도달할 수 없음: thinking OFF");
        };
        switch (style) {
            case REASONING_EFFORT -> body.put("reasoning_effort", effort);
            case OPENROUTER_REASONING -> body.put("reasoning", Map.of("effort", effort));
            case ZAI_THINKING -> throw new IllegalStateException("도달할 수 없음: 위에서 처리됨");
        }
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
