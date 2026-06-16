package com.example.dvely.agent.application.result;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentSubmission;

public record AgentSubmitResult(
        AgentPlan plan,
        AgentSubmission submission
) {
}
