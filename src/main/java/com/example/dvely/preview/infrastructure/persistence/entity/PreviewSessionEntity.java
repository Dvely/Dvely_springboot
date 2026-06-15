package com.example.dvely.preview.infrastructure.persistence.entity;

import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "preview_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PreviewSessionEntity {

    @Id
    @Column(name = "preview_session_id", length = 36)
    private String id;

    @Column(name = "access_token", nullable = false, unique = true, length = 64)
    private String accessToken;

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "chat_session_id")
    private Long conversationId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "container_id", nullable = false, length = 128)
    private String containerId;

    @Column(name = "host_port", nullable = false)
    private int hostPort;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "public_url", nullable = false, length = 1000)
    private String publicUrl;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_accessed_at", nullable = false)
    private LocalDateTime lastAccessedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public PreviewSessionEntity(String id,
                                String accessToken,
                                Long ownerUserId,
                                Long projectId,
                                Long conversationId,
                                String taskId,
                                String containerId,
                                int hostPort,
                                String publicUrl,
                                LocalDateTime expiresAt) {
        this.id = id;
        this.accessToken = accessToken;
        this.ownerUserId = ownerUserId;
        this.projectId = projectId;
        this.conversationId = conversationId;
        this.taskId = taskId;
        this.containerId = containerId;
        this.hostPort = hostPort;
        this.status = PreviewSessionStatus.ACTIVE.name();
        this.publicUrl = publicUrl;
        this.expiresAt = expiresAt;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public void touch(LocalDateTime expiresAt) {
        lastAccessedAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    public void close(PreviewSessionStatus status) {
        this.status = status.name();
    }

    public PreviewSessionInfo toInfo() {
        return new PreviewSessionInfo(
                id,
                ownerUserId,
                projectId,
                conversationId,
                taskId,
                containerId,
                hostPort,
                publicUrl,
                expiresAt
        );
    }
}
