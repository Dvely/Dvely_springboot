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
 * @param hasAnnotated 짚은 곳마다 번호를 그린 화면이 있는지({@code GET …/image?kind=annotated}, 여러 곳일 때)
 * @param marks     짚은 곳들 ①②…(설계 §15.5). 한 곳이면 하나다
 */
public record ShareFeedbackDetailResponse(
        ShareFeedbackResponse feedback,
        Map<String, Object> element,
        List<Note> notes,
        boolean hasCrop,
        boolean hasScreen,
        boolean hasAnnotated,
        List<Mark> marks
) {

    /**
     * 짚은 곳 하나.
     *
     * @param n       번호(1부터). 프롬프트·번호 그린 화면의 번호와 같다
     * @param label   그 곳에서 고른 범위의 이름
     * @param hasCrop 그 곳을 잘라 낸 그림이 있는지({@code GET …/image?kind=crop&mark=n})
     */
    public record Mark(int n, String label, boolean hasCrop) {
    }

    /** 이어진 대화 한 줄. {@code REOPEN} 의 글은 사람이 쓴 것이라 지시가 아니라 데이터다. */
    public record Note(Long noteId, FeedbackAuthorRole authorRole, FeedbackNoteKind kind, String body,
                       LocalDateTime createdAt) {

        static Note from(ShareFeedbackNote note) {
            return new Note(note.id(), note.authorRole(), note.kind(), note.body(), note.createdAt());
        }
    }

    public static ShareFeedbackDetailResponse from(ShareFeedbackView view, Map<String, Object> element) {
        return new ShareFeedbackDetailResponse(ShareFeedbackResponse.from(view), element,
                view.notes().stream().map(Note::from).toList(), view.hasCrop(), view.hasScreen(), view.hasAnnotated(),
                view.marks().stream().map(m -> new Mark(m.n(), m.label(), m.hasCrop())).toList());
    }
}
