package com.example.dvely.chat.infrastructure.persistence.entity;

import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.value.ChatRole;
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

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long id;

    @Column(name = "chat_session_id", nullable = false)
    private Long conversationId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "token_count", nullable = false)
    private long tokenCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    private ChatMessageEntity(Long conversationId, String role, String content, long tokenCount) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.tokenCount = tokenCount;
    }

    public static ChatMessageEntity from(ChatMessage message) {
        return new ChatMessageEntity(
                message.getConversationId(),
                message.getRole().toStorage(),
                message.getContent(),
                message.getTokenCount()
        );
    }

    public ChatMessage toDomain() {
        return new ChatMessage(
                id,
                conversationId,
                ChatRole.fromStorage(role),
                content,
                tokenCount,
                createdAt
        );
    }
}
