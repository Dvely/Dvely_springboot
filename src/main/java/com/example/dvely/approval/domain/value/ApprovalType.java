package com.example.dvely.approval.domain.value;

public enum ApprovalType {
    CHANGE,
    DEPLOYMENT,
    DOMAIN_BINDING,
    INFRA_OPERATION,
    // Track Z (#56): "결과 승인" — approves reflecting an already-EXECUTED task's preview state
    // into main (git 반영), as opposed to the other four types which all gate EXECUTION of a
    // still-pending plan. Created exclusively by ResultApprovalGate after the last CODE step
    // completes (never by AgentOrchestrator.toApprovalType, which only maps plan steps that
    // haven't run yet) — see design z-result-approval-design.md D2/D7.
    RESULT
}
