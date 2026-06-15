package com.example.dvely.chat.domain.model;

import java.time.LocalDateTime;
import java.util.Objects;

public class Conversation {

    public static final String DEFAULT_TITLE = "새 대화";
    private static final int MAX_AUTO_TITLE_LENGTH = 80;

    private final Long id;
    private final Long userId;
    private Long projectId;
    private String title;
    private boolean deleted;
    private LocalDateTime deletedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public Conversation(Long userId, Long projectId) {
        this(
                null,
                userId,
                projectId,
                DEFAULT_TITLE,
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
        this(id, userId, projectId, DEFAULT_TITLE, deleted, deletedAt, createdAt, updatedAt);
    }

    public Conversation(Long id,
                        Long userId,
                        Long projectId,
                        String title,
                        boolean deleted,
                        LocalDateTime deletedAt,
                        LocalDateTime createdAt,
                        LocalDateTime updatedAt) {
        this.id = id;
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.title = requireText(title, "title");
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

    public String getTitle() {
        return title;
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
        softDelete(LocalDateTime.now());
    }

    public void softDelete(LocalDateTime deletedAt) {
        if (deleted) {
            return;
        }
        deleted = true;
        this.deletedAt = Objects.requireNonNull(deletedAt, "deletedAt must not be null");
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

    public boolean assignTitleFromFirstMessage(String content) {
        if (!DEFAULT_TITLE.equals(title)) {
            return false;
        }
        String normalized = requireText(content, "content").replaceAll("\\s+", " ");
        title = normalized.length() <= MAX_AUTO_TITLE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_AUTO_TITLE_LENGTH - 3) + "...";
        return true;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
