package com.example.dvely.agent.application.port.out;

import com.example.dvely.agent.domain.value.LlmUsage;
import java.util.List;
import java.util.Map;

/**
 * @param usage 이 호출이 쓴 토큰. 제공자가 {@code usage} 를 주지 않으면 {@link LlmUsage#NONE}.
 *              반환값에 실어 두는 이유는 호출부(CODE 루프)가 라운드별로 무엇이 캐시에서 읽혔는지
 *              볼 수 있게 하기 위해서다 — 영속화 자체는 제공자 클라이언트가 이미 끝낸다.
 */
public record LlmToolResponse(
        List<ToolCall> toolCalls,
        List<Map<String, Object>> contentBlocks,
        String stopReason,
        LlmUsage usage
) {
    public LlmToolResponse {
        usage = usage == null ? LlmUsage.NONE : usage;
    }

    /** 사용량을 모르는 호출부용(주로 테스트 더블). */
    public LlmToolResponse(List<ToolCall> toolCalls,
                           List<Map<String, Object>> contentBlocks,
                           String stopReason) {
        this(toolCalls, contentBlocks, stopReason, LlmUsage.NONE);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
