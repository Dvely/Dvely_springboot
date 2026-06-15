package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.domain.value.ChatRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentMessageService {

    private final ChatMessageRepository chatMessageRepository;

    @Transactional
    public void appendAssistant(Long conversationId, String content) {
        if (conversationId == null || content == null || content.isBlank()) {
            return;
        }
        chatMessageRepository.save(new ChatMessage(
                conversationId,
                ChatRole.ASSISTANT,
                content.trim(),
                0
        ));
    }

    @Transactional(readOnly = true)
    public List<LlmMessage> getConversationContext(Long conversationId) {
        return chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId)
                .stream()
                .map(message -> new LlmMessage(
                        message.getRole().toStorage(),
                        message.getContent()
                ))
                .toList();
    }
}
