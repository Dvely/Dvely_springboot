package com.example.dvely.chat.domain.repository;

import com.example.dvely.chat.domain.model.ChatMessage;
import java.util.List;

public interface ChatMessageRepository {

    /**
     * 대화의 메시지를 쓴 순서대로. 이름의 {@code CreatedAtAsc} 는 계약("시간 오름차순")이고,
     * 실제 정렬 키는 message_id 다 — created_at 이 {@code DATETIME(0)} 이라 같은 초에 들어온
     * 메시지의 순서가 비결정적이었기 때문이다(#338). created_at 을 정렬 키로 되돌리지 말 것.
     */
    List<ChatMessage> findAllByConversationIdOrderByCreatedAtAsc(Long conversationId);

    /**
     * U6(#341) 6-3: 위와 같은 순서로, 다만 {@code after}(message id, 배타적) 이후부터 최대
     * {@code limit} 건. {@code after} 가 null 이면 처음부터다.
     */
    List<ChatMessage> findPageByConversationId(Long conversationId, Long after, int limit);

    // deleteAllByConversationId 는 없다(#338). chat_messages 의 FK 는 V19 부터
    // ON DELETE CASCADE 라, 대화 행을 지우면 메시지는 DB 가 지운다. 별도 삭제를 두면
    // 엔티티를 N 건 로드해 한 건씩 지운 뒤 CASCADE 가 같은 일을 또 하는 이중 삭제가 된다.

    ChatMessage save(ChatMessage message);
}
