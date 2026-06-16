package com.example.dvely.chat.application.facade;

import com.example.dvely.chat.application.command.ChatCommandService;
import com.example.dvely.chat.application.query.ChatQueryService;
import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatFacade {

    private final ChatCommandService chatCommandService;
    private final ChatQueryService chatQueryService;

    public ConversationResult createConversation(Long userId, Long projectId) {
        return chatCommandService.createConversation(userId, projectId);
    }

    public void deleteConversation(Long userId, Long conversationId) {
        chatCommandService.deleteConversation(userId, conversationId);
    }

    public void permanentlyDeleteConversation(Long userId, Long conversationId) {
        chatCommandService.permanentlyDeleteConversation(userId, conversationId);
    }

    public ConversationResult restoreConversation(Long userId, Long conversationId) {
        return chatCommandService.restoreConversation(userId, conversationId);
    }

    public List<ConversationResult> getConversations(Long userId, Long projectId) {
        return chatQueryService.getConversations(userId, projectId);
    }

    public ConversationResult getConversation(Long userId, Long conversationId) {
        return chatQueryService.getConversation(userId, conversationId);
    }

    public List<ConversationResult> getTrashConversations(Long userId) {
        return chatQueryService.getTrashConversations(userId);
    }

    public MessageResult sendMessage(Long userId, Long conversationId, String content) {
        return chatCommandService.sendMessage(userId, conversationId, content);
    }

    public List<MessageResult> getMessages(Long userId, Long conversationId) {
        return chatQueryService.getMessages(userId, conversationId);
    }
}
