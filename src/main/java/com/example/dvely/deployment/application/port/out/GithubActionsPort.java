package com.example.dvely.deployment.application.port.out;

import java.time.LocalDateTime;
import java.util.List;

public interface GithubActionsPort {

    boolean workflowExists(String userToken, String repoFullName, String workflowFileName);

    void createOrUpdateWorkflow(String userToken, String repoFullName, String workflowFileName, String content);

    default void triggerWorkflow(String userToken, String repoFullName, String workflowFileName, String ref) {
        triggerWorkflow(userToken, repoFullName, workflowFileName, ref, null);
    }

    default void triggerWorkflow(String userToken, String repoFullName, String workflowFileName,
                                 String dispatchRef, String checkoutRef) {
        triggerWorkflow(userToken, repoFullName, workflowFileName, dispatchRef, checkoutRef, null);
    }

    void triggerWorkflow(String userToken, String repoFullName, String workflowFileName,
                         String dispatchRef, String checkoutRef, String correlationId);

    WorkflowRunStatus getLatestRunStatus(String userToken, String repoFullName,
                                         String workflowFileName, LocalDateTime afterTime);

    WorkflowRunMatch findWorkflowRun(
            String userToken,
            String repoFullName,
            String workflowFileName,
            String correlationId,
            String expectedHeadSha,
            LocalDateTime afterTime
    );

    WorkflowRunMatch pollWorkflowRun(
            String userToken,
            String repoFullName,
            String workflowFileName,
            String correlationId,
            String expectedHeadSha,
            LocalDateTime afterTime,
            int maxRetries,
            long retryIntervalMs
    );

    WorkflowRunStatus getWorkflowRunStatus(String userToken, String repoFullName, Long runId);

    DeploymentLogs getJobLogs(String userToken, String repoFullName, Long runId);

    record WorkflowRunStatus(
            Long runId,
            String status,       // queued | in_progress | completed
            String conclusion    // success | failure | cancelled | null(미완료)
    ) {}

    record WorkflowRunMatch(
            Long runId,
            String headSha,
            String status,
            String conclusion
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
