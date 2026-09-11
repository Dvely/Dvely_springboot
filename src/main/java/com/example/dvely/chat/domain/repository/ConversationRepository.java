package com.example.dvely.chat.domain.repository;

import com.example.dvely.chat.domain.model.Conversation;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ConversationRepository {

    List<Conversation> findAllByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc(Long userId, Long projectId);

    List<Conversation> findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(Long userId);

    /**
     * U6(#341) 6-8: 프로젝트의 활성 대화를 전부 휴지통으로. 예전에는 N 건을 읽어 한 건씩 save 했다.
     * 돌려주는 값은 옮긴 행 수다.
     */
    int softDeleteAllByUserIdAndProjectId(Long userId, Long projectId, LocalDateTime deletedAt);

    /** U6 6-8: 프로젝트의 대화를 전부 삭제. 메시지는 FK ON DELETE CASCADE 가 함께 지운다. */
    int deleteAllByUserIdAndProjectId(Long userId, Long projectId);


    Optional<Conversation> findByIdAndUserIdAndDeletedFalse(Long conversationId, Long userId);

    Optional<Conversation> findByIdAndUserId(Long conversationId, Long userId);

    void deleteById(Long conversationId);

    /**
     * 만료된 휴지통 대화를 한 문장으로 지운다(#340 5-9).
     *
     * @return 지워진 대화 수
     */
    int deleteExpiredTrash(LocalDateTime cutoff);

    Conversation save(Conversation conversation);
}
