package com.example.dvely.deployment.domain.model;

import com.example.dvely.deployment.domain.value.AnalysisSource;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A single, immutable analysis of why a deployment failed (U6 design D1/D4). Append-only: there
 * is no update method and no setter — {@code uk_deployment_failure_analyses_history} enforces at
 * most one row per {@code historyId}, and re-analysis is out of scope (design §7). This mirrors
 * how {@code environment.domain.model.EnvironmentVariableHistory} treats audit-style rows as
 * write-once value objects rather than mutable entities.
 */
public class DeploymentFailureAnalysis {

    private final Long id;
    private final Long historyId;
    private final Long userId;
    private final AnalysisSource source;
    private final String summary;
    private final String logExcerpt;
    private final String suggestedFix;
    private final String provider;
    private final String model;
    private final LocalDateTime createdAt;

    /** New-analysis constructor (before persistence assigns id/createdAt). */
    public DeploymentFailureAnalysis(Long historyId,
                                     Long userId,
                                     AnalysisSource source,
                                     String summary,
                                     String logExcerpt,
                                     String suggestedFix,
                                     String provider,
                                     String model) {
        this(null, historyId, userId, source, summary, logExcerpt, suggestedFix, provider, model, null);
    }

    /** Restore-from-storage constructor. */
    public DeploymentFailureAnalysis(Long id,
                                     Long historyId,
                                     Long userId,
                                     AnalysisSource source,
                                     String summary,
                                     String logExcerpt,
                                     String suggestedFix,
                                     String provider,
                                     String model,
                                     LocalDateTime createdAt) {
        this.id = id;
        this.historyId = Objects.requireNonNull(historyId, "historyId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.summary = requireText(summary, "summary");
        this.logExcerpt = Objects.requireNonNull(logExcerpt, "logExcerpt must not be null");
        this.suggestedFix = requireText(suggestedFix, "suggestedFix");
        // provider/model are legitimately null for RULE_BASED analyses (no AI provider involved).
        this.provider = provider;
        this.model = model;
        this.createdAt = createdAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public Long getId()                  { return id; }
    public Long getHistoryId()           { return historyId; }
    public Long getUserId()              { return userId; }
    public AnalysisSource getSource()    { return source; }
    public String getSummary()           { return summary; }
    public String getLogExcerpt()        { return logExcerpt; }
    public String getSuggestedFix()      { return suggestedFix; }
    public String getProvider()          { return provider; }
    public String getModel()             { return model; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
}
