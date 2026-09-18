package com.example.dvely.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.domain.value.LlmUsage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmUsageParserTest {

    @Test
    void readsAnthropicsFourTokenCounts() {
        LlmUsage usage = LlmUsageParser.anthropic(Map.of("usage", Map.of(
                "input_tokens", 120,
                "output_tokens", 45,
                "cache_creation_input_tokens", 4_000,
                "cache_read_input_tokens", 12_000)));

        assertThat(usage.inputTokens()).isEqualTo(120);
        assertThat(usage.outputTokens()).isEqualTo(45);
        assertThat(usage.cacheCreationInputTokens()).isEqualTo(4_000);
        assertThat(usage.cacheReadInputTokens()).isEqualTo(12_000);
        assertThat(usage.billedInputTokens()).isEqualTo(16_120);
        assertThat(usage.totalTokens()).isEqualTo(16_165);
    }

    @Test
    void subtractsCachedTokensFromOpenAisPromptTokens() {
        // prompt_tokens 는 캐시 적중분을 포함한 수이고 Anthropic 의 input_tokens 는 제외한 수다.
        // 그대로 담으면 같은 이름의 칸에 다른 뜻이 섞여 제공자 간 합계가 어긋난다.
        LlmUsage usage = LlmUsageParser.openAiCompatible(Map.of("usage", Map.of(
                "prompt_tokens", 10_000,
                "completion_tokens", 300,
                "prompt_tokens_details", Map.of("cached_tokens", 8_000))));

        assertThat(usage.inputTokens()).isEqualTo(2_000);
        assertThat(usage.cacheReadInputTokens()).isEqualTo(8_000);
        assertThat(usage.billedInputTokens()).isEqualTo(10_000);
        assertThat(usage.totalTokens()).isEqualTo(10_300);
    }

    @Test
    void treatsAMissingUsageBlockAsZeroRatherThanFailing() {
        // 계측이 던지면 이미 성공한 LLM 호출이 통째로 실패한다.
        assertThat(LlmUsageParser.anthropic(Map.of())).isEqualTo(LlmUsage.NONE);
        assertThat(LlmUsageParser.openAiCompatible(Map.of())).isEqualTo(LlmUsage.NONE);
        assertThat(LlmUsageParser.anthropic(Map.of("usage", "이건 객체가 아니다"))).isEqualTo(LlmUsage.NONE);
    }

    @Test
    void survivesCachedTokensLargerThanPromptTokens() {
        // 제공자가 어긋난 수를 줘도 입력이 음수가 되어 합계를 오염시키면 안 된다.
        LlmUsage usage = LlmUsageParser.openAiCompatible(Map.of("usage", Map.of(
                "prompt_tokens", 100,
                "prompt_tokens_details", Map.of("cached_tokens", 999))));

        assertThat(usage.inputTokens()).isZero();
    }

    @Test
    void sumsAcrossCalls() {
        LlmUsage total = LlmUsage.NONE
                .plus(new LlmUsage(10, 1, 0, 0))
                .plus(new LlmUsage(0, 2, 0, 500));

        assertThat(total.totalTokens()).isEqualTo(513);
    }
}
