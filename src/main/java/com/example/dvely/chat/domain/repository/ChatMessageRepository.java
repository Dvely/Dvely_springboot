package com.example.dvely.chat.domain.repository;

import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.value.ChatRole;
import java.util.List;

public interface ChatMessageRepository {

    // 순서는 전부 "쓴 순서" 다. 정렬 키는 created_at 이 아니라 message_id 다 — created_at 이 DATETIME(0) 이라 같은 초에
    // 들어온 메시지의 순서가 비결정적이었기 때문이다(#338). created_at 을 정렬 키로 되돌리지 말 것.
    //
    // 대화 전체를 한 번에 읽는 메서드는 두지 않는다(#625). 유일한 호출자였던 LLM 대화 창은 뒤에서부터 정해진 수만 싣으므로
    // findLatestByConversationId 로 DB 에서 자른다. 화면은 findPageByConversationId 로 나눠 읽는다.

    /**
     * U6(#341) 6-3: 쓴 순서대로, {@code after}(message id, 배타적) 이후부터 최대 {@code limit} 건. {@code after} 가
     * null 이면 처음부터다.
     */
    List<ChatMessage> findPageByConversationId(Long conversationId, Long after, int limit);

    /** 대화의 마지막 {@code limit} 건을 쓴 순서대로(오래된 것 먼저). */
    List<ChatMessage> findLatestByConversationId(Long conversationId, int limit);

    /** 대화에서 {@code role} 이 쓴 마지막 {@code limit} 건을 쓴 순서대로(오래된 것 먼저). */
    List<ChatMessage> findLatestByConversationIdAndRole(Long conversationId, ChatRole role, int limit);

    // deleteAllByConversationId 는 없다(#338). chat_messages 의 FK 는 V19 부터
    // ON DELETE CASCADE 라, 대화 행을 지우면 메시지는 DB 가 지운다. 별도 삭제를 두면
    // 엔티티를 N 건 로드해 한 건씩 지운 뒤 CASCADE 가 같은 일을 또 하는 이중 삭제가 된다.

    ChatMessage save(ChatMessage message);
}
