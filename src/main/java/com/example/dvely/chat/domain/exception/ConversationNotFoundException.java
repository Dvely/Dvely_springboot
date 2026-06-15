package com.example.dvely.chat.domain.exception;

import com.example.dvely.common.exception.NotFoundException;

public class ConversationNotFoundException extends NotFoundException {

    public ConversationNotFoundException(Long conversationId, Long userId) {
        super("Conversation not found. conversationId=" + conversationId + ", userId=" + userId);
    }
}
