package com.example.dvely.chat.domain.repository;

import com.example.dvely.chat.domain.model.ChatMessage;
import java.util.List;

public interface ChatMessageRepository {

    List<ChatMessage> findAllByConversationIdOrderByCreatedAtAsc(Long conversationId);

    void deleteAllByConversationId(Long conversationId);

    ChatMessage save(ChatMessage message);
}
