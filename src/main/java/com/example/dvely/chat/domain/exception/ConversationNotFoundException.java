package com.example.dvely.chat.domain.exception;

public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(Long conversationId, Long userId) {
        super("Conversation not found. conversationId=" + conversationId + ", userId=" + userId);
    }
}
