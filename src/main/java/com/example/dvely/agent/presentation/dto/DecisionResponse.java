package com.example.dvely.agent.presentation.dto;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.domain.value.AiProvider;

import java.util.List;

public record DecisionResponse(
        List<AgentStep> steps,
        String          reasoning,
        AiProvider      aiProvider,
        String          taskId
) {}
