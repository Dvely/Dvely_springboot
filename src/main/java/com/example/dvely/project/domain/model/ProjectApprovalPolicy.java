package com.example.dvely.project.domain.model;

import com.example.dvely.approval.domain.value.ApprovalType;

public class ProjectApprovalPolicy {

    private final Long projectId;
    private boolean changeApprovalRequired;
    private boolean deploymentApprovalRequired;
    private boolean domainApprovalRequired;
    private boolean infraApprovalRequired;
    // Track Z (#56) D4: independent from changeApprovalRequired — a user may trust the plan
    // (CHANGE) but still want to review the actual diff/preview before it lands on main, or vice
    // versa. Defaults to true via the same fail-safe as the other four (no policy row yet ==
    // "review everything").
    private boolean resultApprovalRequired;

    public ProjectApprovalPolicy(Long projectId) {
        this(projectId, true, true, true, true, true);
    }

    /**
     * Pre-Z (#56) 4-field constructor, kept for every existing call site (agent gating, infra
     * config, tests) that has no opinion on the RESULT policy — defaults it to {@code true},
     * matching the all-required fail-safe of {@link #ProjectApprovalPolicy(Long)}.
     */
    public ProjectApprovalPolicy(Long projectId,
                                 boolean changeApprovalRequired,
                                 boolean deploymentApprovalRequired,
                                 boolean domainApprovalRequired,
                                 boolean infraApprovalRequired) {
        this(projectId, changeApprovalRequired, deploymentApprovalRequired, domainApprovalRequired,
                infraApprovalRequired, true);
    }

    public ProjectApprovalPolicy(Long projectId,
                                 boolean changeApprovalRequired,
                                 boolean deploymentApprovalRequired,
                                 boolean domainApprovalRequired,
                                 boolean infraApprovalRequired,
                                 boolean resultApprovalRequired) {
        this.projectId = projectId;
        this.changeApprovalRequired = changeApprovalRequired;
        this.deploymentApprovalRequired = deploymentApprovalRequired;
        this.domainApprovalRequired = domainApprovalRequired;
        this.infraApprovalRequired = infraApprovalRequired;
        this.resultApprovalRequired = resultApprovalRequired;
    }

    /**
     * Exhaustive switch (design D7): adding {@code RESULT} here is what forces every consumer of
     * an {@link ApprovalType} switch to consciously handle it at compile time rather than falling
     * through a default branch — the same mechanism that already protected the original 4 types.
     */
    public boolean requires(ApprovalType type) {
        return switch (type) {
            case CHANGE -> changeApprovalRequired;
            case DEPLOYMENT -> deploymentApprovalRequired;
            case DOMAIN_BINDING -> domainApprovalRequired;
            case INFRA_OPERATION -> infraApprovalRequired;
            case RESULT -> resultApprovalRequired;
            // 정책으로 끌 수 없는 유일한 타입이다. 나머지는 "끄면 사람 확인 없이 그대로 진행"이라는
            // 분명한 대안 동작이 있지만(RESULT 를 끄면 preview 가 main 으로 바로 반영된다), 이 타입은
            // 연결할 저장소 자체가 없는 상태라 끌 경우의 대안이 "아무것도 하지 않는다" 뿐이다.
            // 그러면 컨테이너 TTL 만료와 함께 작업물이 사라지는데, 그것이 바로 이 게이트가 막으려는
            // 상황이므로 정책 스위치를 두면 사고를 켜고 끄는 옵션이 된다.
            case REPOSITORY_BINDING -> true;
            // RDS 등 유료 DB 프로비저닝은 과금이 발생하므로 정책으로 끌 수 없다 — 항상 승인.
            case DATABASE_PROVISION -> true;
        };
    }

    public void update(boolean changeApprovalRequired,
                       boolean deploymentApprovalRequired,
                       boolean domainApprovalRequired,
                       boolean infraApprovalRequired,
                       boolean resultApprovalRequired) {
        this.changeApprovalRequired = changeApprovalRequired;
        this.deploymentApprovalRequired = deploymentApprovalRequired;
        this.domainApprovalRequired = domainApprovalRequired;
        this.infraApprovalRequired = infraApprovalRequired;
        this.resultApprovalRequired = resultApprovalRequired;
    }

    public Long getProjectId() { return projectId; }
    public boolean isChangeApprovalRequired() { return changeApprovalRequired; }
    public boolean isDeploymentApprovalRequired() { return deploymentApprovalRequired; }
    public boolean isDomainApprovalRequired() { return domainApprovalRequired; }
    public boolean isInfraApprovalRequired() { return infraApprovalRequired; }
    public boolean isResultApprovalRequired() { return resultApprovalRequired; }
}
