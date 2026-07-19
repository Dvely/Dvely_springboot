package com.example.dvely.agent.domain.value;

import java.util.Locale;
import java.util.Optional;

/**
 * Whitelist catalog for {@link AgentType#INFRA_OPERATE} (design doc D3/D4, EPIC 15). The LLM only
 * ever supplies an {@code operation} name (one of this enum's constants) plus a free-text
 * instruction — never a resource identifier. This is the entire safety boundary for infra chat
 * commands: any operation string that doesn't parse to a constant here has no execution path at
 * all (no reflection, no dynamic dispatch), and the constants below carry the only classification
 * that decides whether an operation runs, requires approval, or is rejected outright.
 *
 * <p>{@code serviceImpact}/{@code costImpact} feed BI-176/177 detection (approval-window markers,
 * §2.4), {@code supported} marks whether an execution path exists yet in
 * {@code InfraOpsAgentService} — unsupported operations (resource scaling, autoscaling, cleanup)
 * are still classified here so they can be detected and explained, not silently misrouted to CHAT.
 */
public enum InfraOperation {

    //                     serviceImpact  costImpact  supported
    STATUS_CHECK          (false,         false,      true),   // BI-170
    LOG_VIEW              (false,         false,      true),   // BI-171
    FAILURE_ANALYSIS      (false,         false,      true),   // BI-172
    RESTART               (true,          false,      true),   // BI-173 — ACTIVE preview container only
    RESOURCE_SCALING      (true,          true,       false),  // BI-174 drop — detect+reject only
    AUTOSCALING_CHANGE    (false,         true,       false),  // BI-175 drop — detect+reject only
    RESOURCE_CLEANUP      (true,          false,      false);  // PRD §21.2 — EPIC 12 will implement execution

    private final boolean serviceImpact;   // BI-176: possible service interruption/impact
    private final boolean costImpact;      // BI-177: possible cost increase
    private final boolean supported;       // whether an execution path exists this cycle

    InfraOperation(boolean serviceImpact, boolean costImpact, boolean supported) {
        this.serviceImpact = serviceImpact;
        this.costImpact = costImpact;
        this.supported = supported;
    }

    /**
     * Approval is required only for operations that both (a) actually have an execution path and
     * (b) carry a detected impact. Unsupported operations never reach approval — they are
     * rejected with guidance instead (a dead approval that can never execute would be dishonest).
     */
    public boolean approvalRequired() {
        return supported && (serviceImpact || costImpact);
    }

    public boolean isServiceImpact() {
        return serviceImpact;
    }

    public boolean isCostImpact() {
        return costImpact;
    }

    public boolean isSupported() {
        return supported;
    }

    /**
     * Parses the step parameter {@code "operation"} into a catalog entry. Missing, blank, or
     * unrecognized values return {@link Optional#empty()} rather than throwing — the caller
     * (orchestrator / InfraOpsAgentService) treats that as "operation not identified" and responds
     * with guidance instead of failing the task, since a slightly-off LLM output is expected, not
     * exceptional.
     */
    public static Optional<InfraOperation> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Renders the detected impact(s) as user-facing markers, e.g. {@code "[서비스 영향] "} or
     * {@code "[서비스 영향] [비용 증가 가능] "} (both, space-suffixed so callers can prepend directly
     * to a sentence). Empty string when neither impact applies. This is the single place BI-176/177
     * detection becomes visible to the user (approval summary or execution warning, design §2.4).
     */
    public String impactMarkers() {
        StringBuilder markers = new StringBuilder();
        if (serviceImpact) {
            markers.append("[서비스 영향] ");
        }
        if (costImpact) {
            markers.append("[비용 증가 가능] ");
        }
        return markers.toString();
    }
}
