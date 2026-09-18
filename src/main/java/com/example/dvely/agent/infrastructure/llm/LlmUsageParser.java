package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.domain.value.LlmUsage;
import java.util.Map;

/**
 * 제공자 응답의 {@code usage} 블록을 {@link LlmUsage} 로 읽는다.
 *
 * <p>두 가지 형태만 존재한다. Anthropic 은 {@code input_tokens / output_tokens /
 * cache_creation_input_tokens / cache_read_input_tokens}, OpenAI 챗 컴플리션 형식(OpenAI 본체와
 * OpenRouter 경유 GLM)은 {@code prompt_tokens / completion_tokens} 에 캐시 적중분을
 * {@code prompt_tokens_details.cached_tokens} 로 덧붙인다.</p>
 *
 * <p>파싱이 실패하거나 {@code usage} 가 없어도 <b>절대 던지지 않는다.</b> 계측이 빠지는 것은
 * 수치 하나를 잃는 일이지만, 계측이 던지면 이미 성공한 LLM 호출이 통째로 실패한다.</p>
 */
final class LlmUsageParser {

    private LlmUsageParser() {
    }

    /** Anthropic Messages API 응답 전체에서 {@code usage} 를 꺼내 읽는다. */
    static LlmUsage anthropic(Map<String, Object> response) {
        return anthropicUsage(usageBlock(response));
    }

    /** 이미 꺼내 둔 Anthropic {@code usage} 블록. */
    static LlmUsage anthropicUsage(Map<String, Object> usage) {
        if (usage == null) {
            return LlmUsage.NONE;
        }
        return new LlmUsage(
                number(usage, "input_tokens"),
                number(usage, "output_tokens"),
                number(usage, "cache_creation_input_tokens"),
                number(usage, "cache_read_input_tokens")
        );
    }

    /**
     * OpenAI 챗 컴플리션 형식의 {@code usage}.
     *
     * <p>{@code prompt_tokens} 는 캐시 적중분을 포함한 수라서, 캐시 적중분을 빼서 담는다 —
     * 그래야 {@link LlmUsage#billedInputTokens()} 가 Anthropic 쪽과 같은 뜻이 된다.</p>
     */
    static LlmUsage openAiCompatible(Map<String, Object> response) {
        Map<String, Object> usage = usageBlock(response);
        if (usage == null) {
            return LlmUsage.NONE;
        }
        long promptTokens = number(usage, "prompt_tokens");
        long cachedTokens = nestedNumber(usage, "prompt_tokens_details", "cached_tokens");
        return new LlmUsage(
                promptTokens - Math.min(promptTokens, cachedTokens),
                number(usage, "completion_tokens"),
                0,
                cachedTokens
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> usageBlock(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        return response.get("usage") instanceof Map<?, ?> usage ? (Map<String, Object>) usage : null;
    }

    @SuppressWarnings("unchecked")
    private static long nestedNumber(Map<String, Object> usage, String outerKey, String key) {
        return usage.get(outerKey) instanceof Map<?, ?> nested
                ? number((Map<String, Object>) nested, key)
                : 0;
    }

    private static long number(Map<String, Object> source, String key) {
        return source.get(key) instanceof Number value ? value.longValue() : 0;
    }
}
