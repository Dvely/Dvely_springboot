package com.example.dvely.chat.domain.repository;

import com.example.dvely.chat.domain.model.ChatMessagePin;
import com.example.dvely.chat.domain.model.ChatMessagePinLabel;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 사용자 메시지에 붙은 짚은 곳(설계 §16.6). 메시지를 지우면 FK CASCADE 로 함께 지워진다. */
public interface ChatMessagePinRepository {

    void saveAll(Long messageId, List<ChatMessagePin> pins);

    /** 번호 순서. 없으면 빈 목록 */
    List<ChatMessagePin> findByMessageId(Long messageId);

    /** 메시지 id → 칩(번호 순서). 짚은 곳이 없는 메시지는 맵에 없다 */
    Map<Long, List<ChatMessagePinLabel>> findLabelsByMessageIds(Collection<Long> messageIds);

    /**
     * 그 태스크를 낳은 사용자 발화에 붙은 곳. 같은 태스크에 사용자 줄이 여럿이면(되묻기 답 등) 짚은 곳이 있는 가장 앞 줄의 것.
     * 없으면 빈 목록
     */
    List<ChatMessagePin> findByTaskId(String taskId);
}
