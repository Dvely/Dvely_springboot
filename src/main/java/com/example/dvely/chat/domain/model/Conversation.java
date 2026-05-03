package com.example.dvely.chat.domain.model;

import java.time.LocalDateTime;
import java.util.Objects;

public class Conversation {

    private final Long id;
    private final Long userId;
    private Long projectId;
    private boolean deleted;
    private LocalDateTime deletedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public Conversation(Long userId, Long projectId) {
        this(
                null,
                userId,
                projectId,
                false,
                null,
                null,
                null
        );
    }

    public Conversation(Long id,
                        Long userId,
                        Long projectId,
                        boolean deleted,
                        LocalDateTime deletedAt,
                        LocalDateTime createdAt,
                        LocalDateTime updatedAt) {
        this.id = id;
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.deleted = deleted;
        this.deletedAt = deletedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void softDelete() {
        if (deleted) {
            return;
        }
        deleted = true;
        deletedAt = LocalDateTime.now();
    }

    public void restore() {
        if (!deleted) {
            return;
        }
        deleted = false;
        deletedAt = null;
    }

    public void restoreToProject(Long projectId) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        restore();
    }
}
