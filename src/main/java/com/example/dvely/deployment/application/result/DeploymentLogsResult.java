package com.example.dvely.deployment.application.result;

import java.util.List;

public record DeploymentLogsResult(
        Long historyId,
        Long workflowRunId,
        List<JobResult> jobs,
        String logText
) {
    public record JobResult(
            Long jobId,
            String name,
            String status,
            String conclusion,
            List<StepResult> steps
    ) {}

    public record StepResult(
            int number,
            String name,
            String status,
            String conclusion
    ) {}
}
