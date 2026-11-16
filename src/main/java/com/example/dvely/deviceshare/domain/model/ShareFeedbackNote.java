package com.example.dvely.deviceshare.domain.model;

import com.example.dvely.deviceshare.domain.value.FeedbackAuthorRole;
import com.example.dvely.deviceshare.domain.value.FeedbackNoteKind;
import java.time.LocalDateTime;

/** 피드백에 이어진 대화 한 줄. 비밀이 없다. */
public record ShareFeedbackNote(Long id, Long feedbackId, FeedbackAuthorRole authorRole, FeedbackNoteKind kind,
                                String body, LocalDateTime createdAt) {
}
