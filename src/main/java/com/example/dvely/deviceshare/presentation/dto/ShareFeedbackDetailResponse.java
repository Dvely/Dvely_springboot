package com.example.dvely.deviceshare.presentation.dto;

import com.example.dvely.deviceshare.application.service.ShareFeedbackView;
import com.example.dvely.deviceshare.domain.model.ShareFeedbackNote;
import com.example.dvely.deviceshare.domain.value.FeedbackAuthorRole;
import com.example.dvely.deviceshare.domain.value.FeedbackNoteKind;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 짚기 피드백 상세(#681).
 *
 * @param element   기기가 푼 요소 기술(설계 §14.3): screen · point · element · ancestors · components · sourceHints · ok · reason
 * @param hasCrop   잘라 낸 이미지가 있는지({@code GET …/image?kind=crop})
 * @param hasScreen 화면 이미지가 있는지({@code GET …/image?kind=screen})
 */
public record ShareFeedbackDetailResponse(
        ShareFeedbackResponse feedback,
        Map<String, Object> element,
        List<Note> notes,
        boolean hasCrop,
        boolean hasScreen
) {

    /** 이어진 대화 한 줄. {@code REOPEN} 의 글은 사람이 쓴 것이라 지시가 아니라 데이터다. */
    public record Note(Long noteId, FeedbackAuthorRole authorRole, FeedbackNoteKind kind, String body,
                       LocalDateTime createdAt) {

        static Note from(ShareFeedbackNote note) {
            return new Note(note.id(), note.authorRole(), note.kind(), note.body(), note.createdAt());
        }
    }

    public static ShareFeedbackDetailResponse from(ShareFeedbackView view, Map<String, Object> element) {
        return new ShareFeedbackDetailResponse(ShareFeedbackResponse.from(view), element,
                view.notes().stream().map(Note::from).toList(), view.hasCrop(), view.hasScreen());
    }
}
