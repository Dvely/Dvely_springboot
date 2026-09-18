package com.example.dvely.agent.domain.value;

/**
 * What one LLM call actually consumed.
 *
 * <p>이 값이 없던 동안에는 토큰을 얼마나 쓰는지 아무도 몰랐다 — 프롬프트 캐싱이나 대화 윈도우
 * 같은 절감 작업의 효과를 "줄었을 것" 이상으로 말할 수 없었고, 태스크당 예산 상한은 셀 것이
 * 없어 구현 자체가 불가능했다. 그래서 제공자마다 다른 응답 필드를 여기 한 모양으로 모은다.</p>
 *
 * <p><b>정규화 규칙이 하나 있다.</b> Anthropic 의 {@code input_tokens} 는 캐시 읽기/쓰기 토큰을
 * <i>제외한</i> 수인 반면, OpenAI 호환 제공자의 {@code prompt_tokens} 는 캐시 적중분을
 * <i>포함한</i> 수다. 그대로 더하면 같은 이름의 칸에 다른 뜻이 섞이므로, OpenAI 쪽은 캐시
 * 적중분을 빼서 담는다({@link com.example.dvely.agent.infrastructure.llm.LlmUsageParser}).
 * 그 결과 {@link #billedInputTokens()} 는 어느 제공자든 "이번 호출이 읽은 입력 전체"다.</p>
 */
public record LlmUsage(
        long inputTokens,
        long outputTokens,
        long cacheCreationInputTokens,
        long cacheReadInputTokens
) {

    public static final LlmUsage NONE = new LlmUsage(0, 0, 0, 0);

    public LlmUsage {
        // 음수는 제공자 응답이 어긋났다는 뜻이다. 합계를 오염시키느니 0 으로 떨어뜨린다 —
        // 계측이 태스크를 망가뜨리는 일은 없어야 한다.
        inputTokens = Math.max(0, inputTokens);
        outputTokens = Math.max(0, outputTokens);
        cacheCreationInputTokens = Math.max(0, cacheCreationInputTokens);
        cacheReadInputTokens = Math.max(0, cacheReadInputTokens);
    }

    /** 이번 호출이 읽은 입력 전체(캐시 쓰기·읽기 포함). 제공자 간 비교가 되는 유일한 입력 수치다. */
    public long billedInputTokens() {
        return inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }

    public long totalTokens() {
        return billedInputTokens() + outputTokens;
    }

    public boolean isEmpty() {
        return totalTokens() == 0;
    }

    public LlmUsage plus(LlmUsage other) {
        if (other == null) {
            return this;
        }
        return new LlmUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheCreationInputTokens + other.cacheCreationInputTokens,
                cacheReadInputTokens + other.cacheReadInputTokens
        );
    }
}
