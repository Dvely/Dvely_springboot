package com.example.dvely.chat.domain.model;

import com.example.dvely.chat.domain.value.ChatMessageKind;
import com.example.dvely.chat.domain.value.ChatRole;
import java.time.LocalDateTime;
import java.util.Objects;

public class ChatMessage {

    private final Long id;
    private final Long conversationId;
    private final ChatRole role;
    private final String content;
    private final long tokenCount;

    /** 줄의 종류. null 이면 지정되지 않음 — 화면은 평범한 어시스턴트 줄로 다룬다. */
    private final ChatMessageKind kind;

    private final LocalDateTime createdAt;

    public ChatMessage(Long conversationId, ChatRole role, String content, long tokenCount) {
        this(null, conversationId, role, content, tokenCount, null, null);
    }

    public ChatMessage(Long conversationId,
                       ChatRole role,
                       String content,
                       long tokenCount,
                       ChatMessageKind kind) {
        this(null, conversationId, role, content, tokenCount, kind, null);
    }

    public ChatMessage(Long id,
                       Long conversationId,
                       ChatRole role,
                       String content,
                       long tokenCount,
                       LocalDateTime createdAt) {
        this(id, conversationId, role, content, tokenCount, null, createdAt);
    }

    public ChatMessage(Long id,
                       Long conversationId,
                       ChatRole role,
                       String content,
                       long tokenCount,
                       ChatMessageKind kind,
                       LocalDateTime createdAt) {
        this.id = id;
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.tokenCount = tokenCount;
        this.kind = kind;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public ChatRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public long getTokenCount() {
        return tokenCount;
    }

    public ChatMessageKind getKind() {
        return kind;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
