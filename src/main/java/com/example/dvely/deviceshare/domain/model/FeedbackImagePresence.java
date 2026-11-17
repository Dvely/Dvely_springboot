package com.example.dvely.deviceshare.domain.model;

import java.util.List;

/**
 * 피드백 이미지가 있는지. 바이트는 읽지 않는다.
 *
 * @param marks 그림이 있는 ②부터의 짚은 곳 번호
 */
public record FeedbackImagePresence(boolean crop, boolean screen, boolean annotated, List<Integer> marks) {

    public static final FeedbackImagePresence NONE = new FeedbackImagePresence(false, false, false, List.of());

    public FeedbackImagePresence {
        marks = marks == null ? List.of() : List.copyOf(marks);
    }
}
