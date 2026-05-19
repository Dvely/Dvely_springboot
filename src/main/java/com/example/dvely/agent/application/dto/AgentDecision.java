package com.example.dvely.agent.application.dto;

import com.example.dvely.agent.domain.value.AgentType;
import java.util.Map;

public record AgentDecision(
        AgentType agentType,
        Map<String, String> parameters,
        String reasoning
) {}
