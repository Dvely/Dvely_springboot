package com.example.dvely.deviceshare.infrastructure.persistence.entity;

import com.example.dvely.deviceshare.domain.model.ShareFeedbackNote;
import com.example.dvely.deviceshare.domain.value.FeedbackAuthorRole;
import com.example.dvely.deviceshare.domain.value.FeedbackNoteKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** JPA row for {@code device_share_feedback_notes}. */
@Entity
@Table(name = "device_share_feedback_notes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShareFeedbackNoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "note_id")
    private Long id;

    @Column(name = "feedback_id", nullable = false)
    private Long feedbackId;

    @Column(name = "author_role", nullable = false, length = 8)
    private String authorRole;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static ShareFeedbackNoteEntity from(ShareFeedbackNote note, LocalDateTime now) {
        ShareFeedbackNoteEntity e = new ShareFeedbackNoteEntity();
        e.feedbackId = note.feedbackId();
        e.authorRole = note.authorRole().name();
        e.kind = note.kind().name();
        e.body = note.body() == null ? "" : note.body();
        e.createdAt = now;
        return e;
    }

    public ShareFeedbackNote toDomain() {
        return new ShareFeedbackNote(id, feedbackId, FeedbackAuthorRole.valueOf(authorRole),
                FeedbackNoteKind.valueOf(kind), body, createdAt);
    }
}
