package com.example.dvely.project.domain.model;

import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * The currently applied infrastructure configuration for a project (desired state — actual
 * cloud provisioning is EPIC 15's concern). One row per project (project_id is the PK, see V25),
 * mirroring the {@code ProjectCloudConnectionSetting} shape: a plain constructor pair (new vs.
 * restored-from-persistence) rather than Lombok, since this class needs a mutation method
 * ({@link #apply}) that a pure data-holder record cannot express.
 */
public class ProjectInfrastructureSetting {

    private final Long projectId;
    private InfrastructureConfiguration configuration;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public ProjectInfrastructureSetting(Long projectId, InfrastructureConfiguration configuration) {
        this(projectId, configuration, null, null);
    }

    public ProjectInfrastructureSetting(Long projectId,
                                        InfrastructureConfiguration configuration,
                                        LocalDateTime createdAt,
                                        LocalDateTime updatedAt) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void apply(InfrastructureConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    public Long getProjectId() {
        return projectId;
    }

    public InfrastructureConfiguration getConfiguration() {
        return configuration;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
