package com.example.dvely.agent.application.port.out;

import java.util.List;
import java.util.Map;

public record LlmToolResponse(
        List<ToolCall> toolCalls,
        List<Map<String, Object>> contentBlocks,
        String stopReason
) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
