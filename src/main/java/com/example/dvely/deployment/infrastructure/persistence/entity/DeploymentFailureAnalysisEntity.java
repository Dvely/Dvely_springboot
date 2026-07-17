package com.example.dvely.deployment.infrastructure.persistence.entity;

import com.example.dvely.deployment.domain.model.DeploymentFailureAnalysis;
import com.example.dvely.deployment.domain.value.AnalysisSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * JPA row for {@code deployment_failure_analyses} (V24 migration). Append-only — no
 * {@code updateFrom(...)}: {@code uk_deployment_failure_analyses_history} caps this at one row
 * per {@code historyId}, and the domain model has no mutators to update from anyway.
 */
@Entity
@Table(name = "deployment_failure_analyses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeploymentFailureAnalysisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_id")
    private Long id;

    @Column(name = "history_id", nullable = false)
    private Long historyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "log_excerpt", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String logExcerpt;

    @Column(name = "suggested_fix", nullable = false, columnDefinition = "TEXT")
    private String suggestedFix;

    @Column(name = "provider", length = 20)
    private String provider;

    @Column(name = "model", length = 100)
    private String model;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    private DeploymentFailureAnalysisEntity(Long historyId,
                                            Long userId,
                                            String source,
                                            String summary,
                                            String logExcerpt,
                                            String suggestedFix,
                                            String provider,
                                            String model) {
        this.historyId = historyId;
        this.userId = userId;
        this.source = source;
        this.summary = summary;
        this.logExcerpt = logExcerpt;
        this.suggestedFix = suggestedFix;
        this.provider = provider;
        this.model = model;
    }

    public static DeploymentFailureAnalysisEntity from(DeploymentFailureAnalysis analysis) {
        return new DeploymentFailureAnalysisEntity(
                analysis.getHistoryId(),
                analysis.getUserId(),
                analysis.getSource().name(),
                analysis.getSummary(),
                analysis.getLogExcerpt(),
                analysis.getSuggestedFix(),
                analysis.getProvider(),
                analysis.getModel()
        );
    }

    public DeploymentFailureAnalysis toDomain() {
        return new DeploymentFailureAnalysis(
                id,
                historyId,
                userId,
                AnalysisSource.valueOf(source),
                summary,
                logExcerpt,
                suggestedFix,
                provider,
                model,
                createdAt
        );
    }
}
