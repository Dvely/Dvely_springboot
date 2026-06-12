package com.example.dvely.chat.infrastructure.persistence.entity;

import com.example.dvely.chat.domain.model.Conversation;
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
@Table(name = "chat_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_session_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private ConversationEntity(Long userId,
                               Long projectId,
                               String title,
                               boolean deleted,
                               LocalDateTime deletedAt) {
        this.userId = userId;
        this.projectId = projectId;
        this.title = title;
        this.deleted = deleted;
        this.deletedAt = deletedAt;
    }

    public static ConversationEntity from(Conversation conversation) {
        return new ConversationEntity(
                conversation.getUserId(),
                conversation.getProjectId(),
                conversation.getTitle(),
                conversation.isDeleted(),
                conversation.getDeletedAt()
        );
    }

    public void updateFrom(Conversation conversation) {
        this.userId = conversation.getUserId();
        this.projectId = conversation.getProjectId();
        this.title = conversation.getTitle();
        this.deleted = conversation.isDeleted();
        this.deletedAt = conversation.getDeletedAt();
    }

    public Conversation toDomain() {
        return new Conversation(
                id,
                userId,
                projectId,
                title,
                deleted,
                deletedAt,
                createdAt,
                updatedAt
        );
    }
}
