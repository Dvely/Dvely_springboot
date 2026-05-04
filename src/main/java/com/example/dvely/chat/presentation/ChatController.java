package com.example.dvely.chat.presentation;

import com.example.dvely.chat.application.facade.ChatFacade;
import com.example.dvely.chat.infrastructure.mapper.ChatMapper;
import com.example.dvely.chat.presentation.dto.ConversationResponse;
import com.example.dvely.chat.presentation.dto.MessageResponse;
import com.example.dvely.chat.presentation.dto.SendMessageRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatFacade chatFacade;
    private final ChatMapper chatMapper;

    @GetMapping("/api/v1/projects/{projectId}/conversations")
    public List<ConversationResponse> getConversations(@AuthenticationPrincipal Long userId,
                                                       @PathVariable Long projectId) {
        return chatFacade.getConversations(userId, projectId).stream()
                .map(chatMapper::toConversationResponse)
                .toList();
    }

    @PostMapping("/api/v1/projects/{projectId}/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse createConversation(@AuthenticationPrincipal Long userId,
                                                   @PathVariable Long projectId) {
        return chatMapper.toConversationResponse(chatFacade.createConversation(userId, projectId));
    }

    @GetMapping("/api/v1/conversations/{conversationId}")
    public ConversationResponse getConversation(@AuthenticationPrincipal Long userId,
                                                @PathVariable Long conversationId) {
        return chatMapper.toConversationResponse(chatFacade.getConversation(userId, conversationId));
    }

    @DeleteMapping("/api/v1/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(@AuthenticationPrincipal Long userId,
                                   @PathVariable Long conversationId) {
        chatFacade.deleteConversation(userId, conversationId);
    }

    @GetMapping("/api/v1/trash/conversations")
    public List<ConversationResponse> getTrashConversations(@AuthenticationPrincipal Long userId) {
        return chatFacade.getTrashConversations(userId).stream()
                .map(chatMapper::toConversationResponse)
                .toList();
    }

    @PostMapping("/api/v1/trash/conversations/{conversationId}/restore")
    public ConversationResponse restoreConversation(@AuthenticationPrincipal Long userId,
                                                    @PathVariable Long conversationId) {
        return chatMapper.toConversationResponse(chatFacade.restoreConversation(userId, conversationId));
    }

    @GetMapping("/api/v1/conversations/{conversationId}/messages")
    public List<MessageResponse> getMessages(@AuthenticationPrincipal Long userId,
                                             @PathVariable Long conversationId) {
        return chatFacade.getMessages(userId, conversationId).stream()
                .map(chatMapper::toMessageResponse)
                .toList();
    }

    @PostMapping("/api/v1/conversations/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse sendMessage(@AuthenticationPrincipal Long userId,
                                       @PathVariable Long conversationId,
                                       @Valid @RequestBody SendMessageRequest request) {
        return chatMapper.toMessageResponse(chatFacade.sendMessage(userId, conversationId, request.content()));
    }
}
