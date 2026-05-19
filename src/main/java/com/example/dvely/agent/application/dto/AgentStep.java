package com.example.dvely.agent.application.dto;

import com.example.dvely.agent.domain.value.AgentType;
import java.util.Map;

public record AgentStep(
        AgentType agentType,
        Map<String, String> parameters
) {}
