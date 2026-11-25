package com.example.dvely.chat.application.facade;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.chat.application.command.ChatCommandService;
import com.example.dvely.chat.application.pin.PreviewPinInput;
import com.example.dvely.chat.application.query.ChatQueryService;
import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.common.paging.CursorPage;
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

    public MessageResult sendMessage(Long userId, Long conversationId, String content, AiProvider aiProvider) {
        return sendMessage(userId, conversationId, content, aiProvider, List.of());
    }

    /** 서버 프리뷰에서 짚은 곳(§16.6)을 함께 보낸다 */
    public MessageResult sendMessage(Long userId, Long conversationId, String content, AiProvider aiProvider,
                                     List<PreviewPinInput> pins) {
        return chatCommandService.sendMessage(userId, conversationId, content, aiProvider, pins);
    }

    public CursorPage<MessageResult> getMessages(Long userId,
                                                 Long conversationId,
                                                 Integer limit,
                                                 String after) {
        return chatQueryService.getMessages(userId, conversationId, limit, after);
    }
}
