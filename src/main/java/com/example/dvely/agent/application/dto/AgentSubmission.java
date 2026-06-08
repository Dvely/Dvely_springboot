package com.example.dvely.agent.application.dto;

import java.util.List;

public record AgentSubmission(
        String taskId,
        TaskStatus status,
        List<Long> approvalIds
) {
}
