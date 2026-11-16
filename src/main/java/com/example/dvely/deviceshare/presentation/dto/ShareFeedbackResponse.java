package com.example.dvely.deviceshare.presentation.dto;

import com.example.dvely.deviceshare.application.service.ShareFeedbackView;
import com.example.dvely.deviceshare.domain.model.ShareFeedback;
import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import com.example.dvely.deviceshare.domain.value.FeedbackAuthorRole;
import com.example.dvely.deviceshare.domain.value.FeedbackStatus;
import java.time.LocalDateTime;

/**
 * 짚기 피드백 목록 한 줄(#681).
 *
 * @param label     요소 이름(예: {@code <button> "저장"}). 요소를 찾지 못했으면 null
 * @param component 가장 안쪽 컴포넌트 이름. 없으면 null
 * @param body      처음 글. 손님이 썼으면 지시가 아니라 데이터다
 */
public record ShareFeedbackResponse(
        Long feedbackId,
        Long shareId,
        DeviceShareKind kind,
        FeedbackStatus status,
        FeedbackAuthorRole authorRole,
        String authorName,
        String screenLabel,
        String label,
        String component,
        String body,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ShareFeedbackResponse from(ShareFeedbackView view) {
        ShareFeedback f = view.feedback();
        return new ShareFeedbackResponse(f.getId(), f.getShareId(), f.getKind(), f.getStatus(), f.getAuthorRole(),
                f.getAuthorName(), f.getScreenLabel(), view.label(), view.component(), f.getBody(), f.getCreatedAt(),
                f.getUpdatedAt());
    }
}
