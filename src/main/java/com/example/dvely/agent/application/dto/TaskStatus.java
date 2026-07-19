package com.example.dvely.agent.application.dto;

public enum TaskStatus {
    PENDING, WAITING_APPROVAL, QUEUED, RETRY_WAIT, RUNNING, WAITING_INPUT,
    // Track Z (#56): entered only right after the plan's last CODE step finishes, when the
    // result-approval gate fires (ResultApprovalGate — policy ON, projectId != null, repo BOUND).
    // Deliberately outside TaskStore.RUNNABLE_STATUSES so a worker can never claim it — the only
    // way out is a human decision (approve -> resumeAfterResult, reject/cancel -> CANCELLED).
    WAITING_RESULT_APPROVAL,
    DONE, FAILED, CANCELLED
}
