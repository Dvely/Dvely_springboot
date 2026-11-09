package com.example.dvely.chat.infrastructure.persistence.repository;

import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.domain.value.ChatRole;
import com.example.dvely.chat.infrastructure.persistence.entity.ChatMessageEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChatMessageRepositoryAdapter implements ChatMessageRepository {

    private final SpringDataChatMessageRepository springDataChatMessageRepository;

    @Override
    public List<ChatMessage> findPageByConversationId(Long conversationId, Long after, int limit) {
        return springDataChatMessageRepository
                .findPageByConversationId(conversationId, after, PageRequest.of(0, limit))
                .stream()
                .map(ChatMessageEntity::toDomain)
                .toList();
    }

    @Override
    public List<ChatMessage> findLatestByConversationId(Long conversationId, int limit) {
        return oldestFirst(springDataChatMessageRepository
                .findLatestByConversationId(conversationId, PageRequest.of(0, limit)));
    }

    @Override
    public List<ChatMessage> findLatestByConversationIdAndRole(Long conversationId, ChatRole role, int limit) {
        return oldestFirst(springDataChatMessageRepository
                .findLatestByConversationIdAndRole(conversationId, role.toStorage(), PageRequest.of(0, limit)));
    }

    /** 최신 것부터 읽은 것을 쓴 순서로 돌린다. */
    private static List<ChatMessage> oldestFirst(List<ChatMessageEntity> newestFirst) {
        return newestFirst.reversed().stream()
                .map(ChatMessageEntity::toDomain)
                .toList();
    }

    @Override
    public ChatMessage save(ChatMessage message) {
        ChatMessageEntity entity = ChatMessageEntity.from(message);
        return springDataChatMessageRepository.save(entity).toDomain();
    }
}
