package com.example.dvely.project.application.result;

import com.example.dvely.agent.application.dto.AgentSubmission;

public record ProjectCreationResult(
        ProjectDetailResult project,
        AgentSubmission generation
) {
}
