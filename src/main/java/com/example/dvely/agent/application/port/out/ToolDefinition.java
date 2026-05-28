package com.example.dvely.agent.application.port.out;

import java.util.Map;

public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema
) {}
