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
    RESULT,
    // "저장소 연결 승인" — the NOT_BOUND counterpart of RESULT, created by RepositoryBindingGate at
    // the same position (right after the plan's last CODE step). RESULT asks "reflect this preview
    // into main?" for a project that already has a repository; this one asks "create and connect a
    // repository at all?" for one that has none. Exactly one of the two can fire for a given task,
    // since they branch on the same repositoryBindingStatus.
    //
    // Unlike every other type here, approving this one carries a value: the repository name, passed
    // as the optional body of POST /approvals/{id}/approve. Absent a name it falls back to the
    // project-name-derived candidate already shown in the approval summary.
    REPOSITORY_BINDING,
    DATABASE_PROVISION,
    // EC2 백엔드 서버 프로비저닝(C2). RDS 처럼 사용자 AWS 계정에 과금 자원(인스턴스)을 만든다 —
    // 정책으로 끌 수 없고 항상 승인. 실제 인스턴스 생성은 승인 후 핸들러가 시작한다(비동기).
    SERVER_PROVISION
}
