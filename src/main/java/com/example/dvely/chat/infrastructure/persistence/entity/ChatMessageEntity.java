package com.example.dvely.chat.infrastructure.persistence.entity;

import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.value.ChatMessageKind;
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

    /** 줄의 종류. 이 칼럼 이전 행과 종류가 없는 줄은 null 이다. */
    @Column(name = "kind", length = 40)
    private String kind;

    /** 이 줄을 만든 에이전트 태스크. 태스크와 무관한 줄과 이 칼럼 이전 행은 null 이다. */
    @Column(name = "task_id", length = 64)
    private String taskId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    private ChatMessageEntity(Long conversationId, String role, String content, long tokenCount,
                              String kind, String taskId) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.tokenCount = tokenCount;
        this.kind = kind;
        this.taskId = taskId;
    }

    public static ChatMessageEntity from(ChatMessage message) {
        return new ChatMessageEntity(
                message.getConversationId(),
                message.getRole().toStorage(),
                message.getContent(),
                message.getTokenCount(),
                message.getKind() == null ? null : message.getKind().name(),
                message.getTaskId()
        );
    }

    /**
     * 모르는 종류가 저장돼 있어도(구버전이 쓴 값, 롤백 중인 배포) 조회를 깨뜨리지 않는다 —
     * 종류는 화면을 꾸미는 부가 정보라 없으면 예전처럼 보이는 것으로 충분하다.
     */
    private static ChatMessageKind parseKind(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return ChatMessageKind.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public ChatMessage toDomain() {
        return new ChatMessage(
                id,
                conversationId,
                ChatRole.fromStorage(role),
                content,
                tokenCount,
                parseKind(kind),
                taskId,
                createdAt
        );
    }
}
