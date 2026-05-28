package com.example.dvely.agent.application.port.out;

import java.util.Map;

public record ToolCall(
        String id,
        String name,
        Map<String, Object> input
) {}
