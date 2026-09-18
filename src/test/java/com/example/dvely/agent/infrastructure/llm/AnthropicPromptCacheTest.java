package com.example.dvely.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 캐시 브레이크포인트는 <b>조용히</b> 틀린다 — 위치가 어긋나도 요청은 그대로 통과하고 캐시만 안
 * 걸린다. 오류가 나지 않으므로 위치를 못 박는 것 말고는 검증할 방법이 없다.
 */
class AnthropicPromptCacheTest {

    private static final Map<String, Object> EPHEMERAL = Map.of("type", "ephemeral");

    @Test
    void putsOneBreakpointOnTheSystemPrompt() {
        List<Map<String, Object>> blocks = AnthropicPromptCache.systemBlocks("시스템");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).containsEntry("type", "text")
                .containsEntry("text", "시스템")
                .containsEntry("cache_control", EPHEMERAL);
    }

    @Test
    void marksOnlyTheLastToolDefinition() {
        // 도구 목록 전체가 하나의 캐시 단위다. 앞쪽 정의에도 마커를 달면 상한 4개를 도구만으로
        // 써 버려 정작 값을 하는 트랜스크립트 쪽 지점이 남지 않는다.
        List<Map<String, Object>> tools = AnthropicPromptCache.toolsWithCacheControl(List.of(
                Map.of("name", "execute_command"),
                Map.of("name", "write_file"),
                Map.of("name", "read_file")));

        assertThat(tools.get(0)).doesNotContainKey("cache_control");
        assertThat(tools.get(1)).doesNotContainKey("cache_control");
        assertThat(tools.get(2)).containsEntry("cache_control", EPHEMERAL);
    }

    @Test
    void marksTheLastTwoUserTurnsAndNothingElse() {
        List<Map<String, Object>> messages = AnthropicPromptCache.messagesWithRollingCacheControl(List.of(
                userText("요청"),
                assistantBlocks(),
                toolResults("결과 1"),
                assistantBlocks(),
                toolResults("결과 2")));

        assertThat(breakpointCount(messages)).isEqualTo(2);
        assertThat(lastBlockOf(messages.get(4))).containsEntry("cache_control", EPHEMERAL);
        assertThat(lastBlockOf(messages.get(2))).containsEntry("cache_control", EPHEMERAL);
        assertThat(lastBlockOf(messages.get(0))).doesNotContainKey("cache_control");
        assertThat(messages.get(1)).doesNotContainKey("cache_control");
    }

    @Test
    void keepsTotalBreakpointsWithinAnthropicsLimitOfFour() {
        // tools 1 + system 1 + 굴러가는 user 2 = 4. 하나라도 늘면 요청 자체가 거절된다.
        int tools = breakpointCount(AnthropicPromptCache.toolsWithCacheControl(
                List.of(Map.of("name", "a"), Map.of("name", "b"))));
        int system = AnthropicPromptCache.systemBlocks("긴 시스템 프롬프트").size();
        int rolling = breakpointCount(AnthropicPromptCache.messagesWithRollingCacheControl(List.of(
                userText("1"), assistantBlocks(), toolResults("2"),
                assistantBlocks(), toolResults("3"), assistantBlocks(), toolResults("4"))));

        assertThat(tools + system + rolling).isEqualTo(4);
    }

    @Test
    void normalizesEveryTextTurnToBlocksSoTheShapeNeverFlipsBetweenRounds() {
        // 마커가 붙는 턴만 배열로 바꾸면, 같은 턴이 라운드마다 문자열↔배열로 오간다. 접두 일치에
        // 그런 흔들림을 남길 이유가 없다.
        List<Map<String, Object>> messages = AnthropicPromptCache.messagesWithRollingCacheControl(List.of(
                userText("맨 앞"), assistantBlocks(), toolResults("1"),
                assistantBlocks(), toolResults("2"), assistantBlocks(), toolResults("3")));

        assertThat(messages.get(0).get("content")).isInstanceOf(List.class);
        assertThat(lastBlockOf(messages.get(0))).doesNotContainKey("cache_control");
    }

    @Test
    void leavesTheCallersTranscriptUntouched() {
        // 호출부 트랜스크립트에 마커가 쌓이면 라운드마다 마커가 늘어 곧 상한 4개를 넘긴다.
        List<Map<String, Object>> original = new ArrayList<>(List.of(userText("요청")));
        Map<String, Object> before = original.get(0);

        AnthropicPromptCache.messagesWithRollingCacheControl(original);

        assertThat(original.get(0)).isSameAs(before);
        assertThat(before.get("content")).isEqualTo("요청");
    }

    @Test
    void spendsNoBreakpointOnAnEmptyTurn() {
        List<Map<String, Object>> messages = AnthropicPromptCache.messagesWithRollingCacheControl(List.of(
                userText("실제 내용"),
                assistantBlocks(),
                Map.of("role", "user", "content", List.of())));

        assertThat(breakpointCount(messages)).isEqualTo(1);
        assertThat(lastBlockOf(messages.get(0))).containsEntry("cache_control", EPHEMERAL);
    }

    private static Map<String, Object> userText(String text) {
        return Map.of("role", "user", "content", text);
    }

    private static Map<String, Object> assistantBlocks() {
        return Map.of("role", "assistant", "content", List.of(Map.of("type", "text", "text", "ok")));
    }

    private static Map<String, Object> toolResults(String content) {
        return Map.of("role", "user", "content",
                List.of(Map.of("type", "tool_result", "tool_use_id", "t", "content", content)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lastBlockOf(Map<String, Object> message) {
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) message.get("content");
        return blocks.get(blocks.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static int breakpointCount(List<Map<String, Object>> items) {
        int count = 0;
        for (Map<String, Object> item : items) {
            if (item.containsKey("cache_control")) {
                count++;
            }
            if (item.get("content") instanceof List<?> blocks) {
                for (Object block : blocks) {
                    if (block instanceof Map<?, ?> map && map.containsKey("cache_control")) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
