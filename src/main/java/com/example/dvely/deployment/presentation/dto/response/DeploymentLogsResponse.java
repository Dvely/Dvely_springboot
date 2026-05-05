package com.example.dvely.deployment.presentation.dto.response;

import java.util.List;

public record DeploymentLogsResponse(
        Long historyId,
        Long workflowRunId,
        List<JobResponse> jobs,
        String logText
) {
    public record JobResponse(
            Long jobId,
            String name,
            String status,
            String conclusion,
            List<StepResponse> steps
    ) {}

    public record StepResponse(
            int number,
            String name,
            String status,
            String conclusion
    ) {}
}
