package com.example.dvely.chat.infrastructure.mapper;

import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.chat.presentation.dto.ConversationResponse;
import com.example.dvely.chat.presentation.dto.MessageResponse;
import org.springframework.stereotype.Component;

@Component
public class ChatMapper {

    public ConversationResponse toConversationResponse(ConversationResult result) {
        return new ConversationResponse(
                result.conversationId(),
                result.projectId(),
                result.title(),
                result.projectName(),
                result.deleted(),
                result.deletedAt(),
                result.retentionExpiresAt(),
                result.remainingRetentionDays(),
                result.createdAt(),
                result.updatedAt()
        );
    }

    public MessageResponse toMessageResponse(MessageResult result) {
        return new MessageResponse(
                result.messageId(),
                result.conversationId(),
                result.role(),
                result.content(),
                result.tokenCount(),
                result.createdAt()
        );
    }
}
