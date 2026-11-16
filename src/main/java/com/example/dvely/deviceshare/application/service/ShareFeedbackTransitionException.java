package com.example.dvely.deviceshare.application.service;

import com.example.dvely.deviceshare.domain.value.FeedbackStatus;

/** 설계 §14.2 에 없는 상태 변경(또는 그 사이 다른 요청이 먼저 바꿈). 409 {@code DEVICE_SHARE_FEEDBACK_INVALID_TRANSITION}. */
public class ShareFeedbackTransitionException extends RuntimeException {

    public ShareFeedbackTransitionException(FeedbackStatus from, FeedbackStatus to) {
        super("지금 상태(" + FeedbackPromptRenderer.status(from) + ")에서는 '" + FeedbackPromptRenderer.status(to)
                + "'(으)로 바꿀 수 없습니다. 목록을 새로 읽어 주세요.");
    }
}
