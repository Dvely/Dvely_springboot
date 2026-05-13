package com.example.dvely.deployment.application.port.out;

import java.time.LocalDateTime;
import java.util.List;

public interface GithubActionsPort {

    boolean workflowExists(String userToken, String repoFullName, String workflowFileName);

    void createOrUpdateWorkflow(String userToken, String repoFullName, String workflowFileName, String content);

    void triggerWorkflow(String userToken, String repoFullName, String workflowFileName, String ref);

    WorkflowRunStatus getLatestRunStatus(String userToken, String repoFullName,
                                         String workflowFileName, LocalDateTime afterTime);

    /**
     * dispatch 직후 run_id가 생성될 때까지 폴링한다.
     * run_id를 얻으면 즉시 반환하고, 최대 시도 내에 얻지 못하면 null을 반환한다.
     */
    Long pollRunId(String userToken, String repoFullName, String workflowFileName,
                   LocalDateTime afterTime, int maxRetries, long retryIntervalMs);

    DeploymentLogs getJobLogs(String userToken, String repoFullName, Long runId);

    record WorkflowRunStatus(
            Long runId,
            String status,       // queued | in_progress | completed
            String conclusion    // success | failure | cancelled | null(미완료)
    ) {}

    record DeploymentLogs(
            Long runId,
            List<JobInfo> jobs,
            String logText
    ) {}

    record JobInfo(
            Long jobId,
            String name,
            String status,
            String conclusion,
            List<StepInfo> steps
    ) {}

    record StepInfo(
            int number,
            String name,
            String status,
            String conclusion
    ) {}
}
