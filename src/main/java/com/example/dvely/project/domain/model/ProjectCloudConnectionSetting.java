package com.example.dvely.project.domain.model;

import java.time.LocalDateTime;
import java.util.Objects;

public class ProjectCloudConnectionSetting {

    private final Long projectId;
    private Long cloudConnectionId;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public ProjectCloudConnectionSetting(Long projectId, Long cloudConnectionId) {
        this(projectId, cloudConnectionId, null, null);
    }

    public ProjectCloudConnectionSetting(Long projectId,
                                         Long cloudConnectionId,
                                         LocalDateTime createdAt,
                                         LocalDateTime updatedAt) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.cloudConnectionId = Objects.requireNonNull(cloudConnectionId, "cloudConnectionId must not be null");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void select(Long cloudConnectionId) {
        this.cloudConnectionId = Objects.requireNonNull(cloudConnectionId, "cloudConnectionId must not be null");
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getCloudConnectionId() {
        return cloudConnectionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
