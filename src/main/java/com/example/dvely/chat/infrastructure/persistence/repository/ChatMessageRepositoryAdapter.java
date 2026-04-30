package com.example.dvely.chat.infrastructure.persistence.repository;

import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.infrastructure.persistence.entity.ChatMessageEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChatMessageRepositoryAdapter implements ChatMessageRepository {

    private final SpringDataChatMessageRepository springDataChatMessageRepository;

    @Override
    public List<ChatMessage> findAllByConversationIdOrderByCreatedAtAsc(Long conversationId) {
        return springDataChatMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(ChatMessageEntity::toDomain)
                .toList();
    }

    @Override
    public ChatMessage save(ChatMessage message) {
        ChatMessageEntity entity = ChatMessageEntity.from(message);
        return springDataChatMessageRepository.save(entity).toDomain();
    }
}
