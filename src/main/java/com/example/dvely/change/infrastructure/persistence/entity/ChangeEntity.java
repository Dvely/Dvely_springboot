package com.example.dvely.change.infrastructure.persistence.entity;

import com.example.dvely.change.application.result.ChangeResult;
import com.example.dvely.change.domain.value.ChangeStatus;
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
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "project_changes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "change_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "chat_session_id")
    private Long conversationId;

    @Column(name = "task_id", nullable = false, unique = true, length = 64)
    private String taskId;

    @Column(name = "preview_session_id", nullable = false, length = 36)
    private String previewSessionId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "diff_text", columnDefinition = "MEDIUMTEXT")
    private String diffText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ChangeEntity(Long ownerUserId,
                        Long projectId,
                        Long conversationId,
                        String taskId,
                        String previewSessionId,
                        String summary,
                        String diffText) {
        this.ownerUserId = ownerUserId;
        this.projectId = projectId;
        this.conversationId = conversationId;
        this.taskId = taskId;
        this.previewSessionId = previewSessionId;
        this.status = ChangeStatus.PREVIEW_READY.name();
        this.summary = summary;
        this.diffText = diffText;
    }

    public void update(String summary, String diffText) {
        this.summary = summary;
        this.diffText = diffText;
    }

    public void markDeployed() {
        status = ChangeStatus.DEPLOYED.name();
    }

    public ChangeResult toResult() {
        return new ChangeResult(
                id,
                projectId,
                conversationId,
                taskId,
                previewSessionId,
                status,
                summary,
                createdAt,
                updatedAt
        );
    }
}
