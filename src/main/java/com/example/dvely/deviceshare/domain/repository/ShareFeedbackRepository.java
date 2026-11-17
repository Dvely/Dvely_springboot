package com.example.dvely.deviceshare.domain.repository;

import com.example.dvely.deviceshare.domain.model.FeedbackImagePresence;
import com.example.dvely.deviceshare.domain.model.FeedbackImages;
import com.example.dvely.deviceshare.domain.model.ShareFeedback;
import com.example.dvely.deviceshare.domain.model.ShareFeedbackNote;
import com.example.dvely.deviceshare.domain.value.FeedbackStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 짚기 피드백 저장소(설계 §14.6). */
public interface ShareFeedbackRepository {

    /** 피드백과 이미지(②부터 짚은 곳의 그림 포함)를 한 트랜잭션으로 저장한다. */
    ShareFeedback save(ShareFeedback feedback, FeedbackImages images);

    Optional<ShareFeedback> findById(Long feedbackId);

    /** 링크의 피드백, 최근 것부터. */
    List<ShareFeedback> findByShare(Long shareId, int limit);

    /** 링크의 이 상태 피드백, 최근 것부터. */
    List<ShareFeedback> findByShareAndStatus(Long shareId, FeedbackStatus status, int limit);

    /** 사용자의 모든 링크에서 이 상태들, 최근에 바뀐 것부터. */
    List<ShareFeedback> findByUser(Long userId, Collection<FeedbackStatus> statuses, int limit);

    long countByShareSince(Long shareId, LocalDateTime since);

    /**
     * 상태가 {@code from} 중 하나일 때만 바꾼다.
     *
     * @return 바꿨는지(그 사이 다른 요청이 바꿨으면 false)
     */
    boolean moveStatus(Long feedbackId, Collection<FeedbackStatus> from, FeedbackStatus to, LocalDateTime now);

    void addNote(ShareFeedbackNote note);

    List<ShareFeedbackNote> findNotes(Long feedbackId);

    /** 이미지 바이트. 행이 없으면 {@link FeedbackImages#NONE}. */
    FeedbackImages findImages(Long feedbackId);

    /** 이미지가 있는지. 바이트는 읽지 않는다 */
    FeedbackImagePresence imagePresence(Long feedbackId);

    /** ②부터 짚은 곳의 잘라 낸 그림. 없으면 빈 값 */
    Optional<byte[]> findMarkCrop(Long feedbackId, int markNo);

    /** 끝난(회수·만료) 시각이 {@code cutoff} 보다 이른 링크의 피드백을 지운다. 이미지·노트는 CASCADE. */
    int deleteForLinksEndedBefore(LocalDateTime cutoff);
}
