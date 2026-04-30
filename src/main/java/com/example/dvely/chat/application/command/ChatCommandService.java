package com.example.dvely.chat.application.command;

import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.chat.domain.exception.ConversationNotFoundException;
import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.chat.domain.value.ChatRole;
import com.example.dvely.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatCommandService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ProjectRepository projectRepository;

    @Transactional
    public ConversationResult createConversation(Long userId, Long projectId) {
        assertProjectAccessible(userId, projectId);
        Conversation conversation = new Conversation(userId, projectId);
        return toResult(conversationRepository.save(conversation));
    }

    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));
        conversation.softDelete();
        conversationRepository.save(conversation);
    }

    @Transactional
    public ConversationResult restoreConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));
        conversation.restore();
        return toResult(conversationRepository.save(conversation));
    }

    @Transactional
    public MessageResult sendMessage(Long userId, Long conversationId, String content) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));

        ChatMessage message = new ChatMessage(conversation.getId(), ChatRole.USER, content, 0);
        return toMessageResult(chatMessageRepository.save(message));
    }

    private void assertProjectAccessible(Long userId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found. projectId=" + projectId + ", ownerUserId=" + userId));
    }

    private ConversationResult toResult(Conversation conversation) {
        return new ConversationResult(
                conversation.getId(),
                conversation.getProjectId(),
                conversation.isDeleted(),
                conversation.getDeletedAt(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private MessageResult toMessageResult(ChatMessage message) {
        return new MessageResult(
                message.getId(),
                message.getConversationId(),
                message.getRole().toStorage(),
                message.getContent(),
                message.getTokenCount(),
                message.getCreatedAt()
        );
    }
}
