package com.example.dvely.agent.infrastructure.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic 요청에 {@code cache_control} 브레이크포인트를 얹는다.
 *
 * <p>캐싱은 <b>접두 일치</b>다. 렌더 순서는 {@code tools → system → messages} 이고, 접두의 한
 * 바이트라도 달라지면 그 뒤 전부가 무효가 된다. 그래서 마커를 붙이기 전에 접두가 실제로 고정인지가
 * 먼저인데, CODE 루프는 그 조건을 만족한다 — 시스템 프롬프트와 도구 정의가 {@code static final}
 * 상수이고, 트랜스크립트는 <b>덧붙이기만</b> 한다(앞부분을 다시 쓰거나 지우는 곳이 없다).</p>
 *
 * <p>브레이크포인트 배치(요청당 최대 4개):</p>
 * <ol>
 *   <li>도구 정의 마지막 블록 — 도구 목록만 덮는다</li>
 *   <li>시스템 프롬프트 — 도구 + 시스템을 함께 덮는다</li>
 *   <li>직전 user 턴, 4. 마지막 user 턴 — 굴러가는 두 지점. 라운드마다 한 칸씩 뒤로 밀리므로,
 *       다음 라운드의 접두는 직전 라운드가 써 둔 지점에서 그대로 적중한다</li>
 * </ol>
 *
 * <p><b>짧은 접두는 조용히 캐시되지 않는다.</b> 최소 캐시 가능 접두는 모델마다 다르다(최신 모델
 * 512 토큰, {@code claude-opus-4-5} 는 4096 토큰). 지금 설정된 기본 모델 기준으로 CODE 의
 * 시스템 프롬프트(4,280자 ≈ 1.1K 토큰)와 도구 정의는 그 최소에 못 미치므로 1·2 번 지점은 항목을
 * 만들지 않는다 — 오류도 나지 않고 과금도 없다. 실제로 값을 하는 것은 3·4 번이고, 트랜스크립트가
 * 최소를 넘는 2~3 라운드째부터 시스템·도구까지 <i>함께</i> 캐시된다(더 긴 접두가 그 앞을 모두
 * 포함하기 때문이다). 1·2 번을 그래도 붙여 두는 이유는 최소 접두가 낮은 모델로 설정을 바꾸면
 * 코드 변경 없이 첫 라운드부터 적중하기 때문이다.</p>
 */
final class AnthropicPromptCache {

    /** 굴러가는 브레이크포인트 개수. tools 1 + system 1 과 합쳐 상한 4를 정확히 채운다. */
    private static final int ROLLING_USER_BREAKPOINTS = 2;

    private static final Map<String, Object> EPHEMERAL = Map.of("type", "ephemeral");

    private AnthropicPromptCache() {
    }

    /** 시스템 프롬프트를 마커 달린 단일 텍스트 블록으로. */
    static List<Map<String, Object>> systemBlocks(String systemPrompt) {
        return List.of(withCacheControl(Map.of(
                "type", "text",
                "text", systemPrompt == null ? "" : systemPrompt
        )));
    }

    /** 도구 목록의 마지막 정의에만 마커를 단다. 목록 전체가 하나의 캐시 단위다. */
    static List<Map<String, Object>> toolsWithCacheControl(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return tools;
        }
        List<Map<String, Object>> marked = new ArrayList<>(tools);
        int last = marked.size() - 1;
        marked.set(last, withCacheControl(marked.get(last)));
        return List.copyOf(marked);
    }

    /**
     * 마지막 user 턴 두 개의 마지막 콘텐츠 블록에 마커를 단다.
     *
     * <p>원본은 건드리지 않는다. 호출부의 트랜스크립트에 마커가 쌓이면 라운드마다 마커 위치가
     * 늘어나 상한 4개를 곧 넘기고, 그 뒤로는 요청 자체가 거절된다.</p>
     *
     * <p>문자열 콘텐츠는 <b>항상</b> 블록 배열로 정규화한다. 마커가 붙는 턴만 블록으로 바꾸면
     * 같은 턴의 모양이 라운드마다 문자열↔배열로 오가는데, 접두 일치에 그런 흔들림을 남길 이유가
     * 없다.</p>
     */
    static List<Map<String, Object>> messagesWithRollingCacheControl(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        List<Map<String, Object>> normalized = new ArrayList<>(messages.size());
        for (Map<String, Object> message : messages) {
            normalized.add(normalizeContent(message));
        }

        int remaining = ROLLING_USER_BREAKPOINTS;
        for (int i = normalized.size() - 1; i >= 0 && remaining > 0; i--) {
            Map<String, Object> message = normalized.get(i);
            if (!"user".equals(message.get("role"))) {
                continue;
            }
            Map<String, Object> marked = markLastContentBlock(message);
            if (marked != null) {
                normalized.set(i, marked);
                remaining--;
            }
        }
        return List.copyOf(normalized);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeContent(Map<String, Object> message) {
        if (!(message.get("content") instanceof String text)) {
            return message;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(message);
        normalized.put("content", List.of(Map.of("type", "text", "text", text)));
        return normalized;
    }

    /** 마커를 붙일 블록이 없으면(빈 콘텐츠) null — 브레이크포인트 하나를 헛되이 쓰지 않는다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> markLastContentBlock(Map<String, Object> message) {
        if (!(message.get("content") instanceof List<?> rawBlocks) || rawBlocks.isEmpty()) {
            return null;
        }
        Object lastBlock = rawBlocks.get(rawBlocks.size() - 1);
        if (!(lastBlock instanceof Map<?, ?> block)) {
            return null;
        }

        List<Object> blocks = new ArrayList<>(rawBlocks);
        blocks.set(blocks.size() - 1, withCacheControl((Map<String, Object>) block));

        Map<String, Object> marked = new LinkedHashMap<>(message);
        marked.put("content", List.copyOf(blocks));
        return marked;
    }

    private static Map<String, Object> withCacheControl(Map<String, Object> block) {
        Map<String, Object> copy = new LinkedHashMap<>(block);
        copy.put("cache_control", EPHEMERAL);
        return copy;
    }
}
