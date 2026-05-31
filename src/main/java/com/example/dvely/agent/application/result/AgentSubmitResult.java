package com.example.dvely.agent.application.result;

import com.example.dvely.agent.application.dto.AgentPlan;

public record AgentSubmitResult(
        String    taskId,
        AgentPlan plan
) {}
