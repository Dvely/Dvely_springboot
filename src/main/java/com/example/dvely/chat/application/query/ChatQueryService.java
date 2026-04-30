package com.example.dvely.chat.application.query;

import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.chat.domain.exception.ConversationNotFoundException;
import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatQueryService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ProjectRepository projectRepository;

    public List<ConversationResult> getConversations(Long userId, Long projectId) {
        assertProjectAccessible(userId, projectId);
        return conversationRepository.findAllByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc(userId, projectId)
                .stream()
                .map(this::toResult)
                .toList();
    }

    public ConversationResult getConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));
        return toResult(conversation);
    }

    public List<ConversationResult> getTrashConversations(Long userId) {
        return conversationRepository.findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(userId)
                .stream()
                .map(this::toResult)
                .toList();
    }

        public List<MessageResult> getMessages(Long userId, Long conversationId) {
        conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
            .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));

        return chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId)
            .stream()
            .map(this::toMessageResult)
            .toList();
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
