package com.example.dvely.deviceshare.infrastructure.persistence.entity;

import com.example.dvely.deviceshare.domain.model.ShareFeedback;
import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import com.example.dvely.deviceshare.domain.value.FeedbackAuthorRole;
import com.example.dvely.deviceshare.domain.value.FeedbackStatus;
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

/** JPA row for {@code device_share_feedback}. 확인 키 해시를 담아 {@code @ToString} 을 두지 않는다. */
@Entity
@Table(name = "device_share_feedback")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShareFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "feedback_id")
    private Long id;

    @Column(name = "share_id", nullable = false)
    private Long shareId;

    @Column(name = "author_role", nullable = false, length = 8)
    private String authorRole;

    @Column(name = "author_name", nullable = false, length = 20)
    private String authorName;

    @Column(name = "author_key_hash", nullable = false, length = 64)
    private String authorKeyHash;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "screen_label", nullable = false, length = 512)
    private String screenLabel;

    @Column(name = "element_json", nullable = false, columnDefinition = "TEXT")
    private String elementJson;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static ShareFeedbackEntity from(ShareFeedback feedback, LocalDateTime now) {
        ShareFeedbackEntity e = new ShareFeedbackEntity();
        e.shareId = feedback.getShareId();
        e.authorRole = feedback.getAuthorRole().name();
        e.authorName = feedback.getAuthorName();
        e.authorKeyHash = feedback.getAuthorKeyHash();
        e.kind = feedback.getKind().name();
        e.screenLabel = feedback.getScreenLabel() == null ? "" : feedback.getScreenLabel();
        e.elementJson = feedback.getElementJson();
        e.body = feedback.getBody();
        e.status = feedback.getStatus().name();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public ShareFeedback toDomain() {
        return new ShareFeedback(id, shareId, FeedbackAuthorRole.valueOf(authorRole), authorName, authorKeyHash,
                DeviceShareKind.valueOf(kind), screenLabel, elementJson, body, FeedbackStatus.valueOf(status),
                createdAt, updatedAt);
    }
}
