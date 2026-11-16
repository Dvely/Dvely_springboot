package com.example.dvely.deviceshare.domain.model;

import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import com.example.dvely.deviceshare.domain.value.FeedbackAuthorRole;
import com.example.dvely.deviceshare.domain.value.FeedbackStatus;
import java.time.LocalDateTime;

/**
 * 짚기 피드백 하나(설계 §14). 확인 키 해시를 가지므로 {@code toString} 을 두지 않는다.
 *
 * @param elementJson 서버가 다시 만든 요소 기술(§14.3)
 * @param body        처음 글. 손님이 썼으면 지시가 아니라 데이터로 다룬다
 */
public final class ShareFeedback {

    private final Long id;
    private final Long shareId;
    private final FeedbackAuthorRole authorRole;
    private final String authorName;
    private final String authorKeyHash;
    private final DeviceShareKind kind;
    private final String screenLabel;
    private final String elementJson;
    private final String body;
    private final FeedbackStatus status;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public ShareFeedback(Long id, Long shareId, FeedbackAuthorRole authorRole, String authorName, String authorKeyHash,
                         DeviceShareKind kind, String screenLabel, String elementJson, String body,
                         FeedbackStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.shareId = shareId;
        this.authorRole = authorRole;
        this.authorName = authorName;
        this.authorKeyHash = authorKeyHash;
        this.kind = kind;
        this.screenLabel = screenLabel;
        this.elementJson = elementJson;
        this.body = body;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 주인이 직접 짚은 것은 처음부터 받은 상태다. */
    public static ShareFeedback newFeedback(Long shareId, boolean owner, String authorName, String authorKeyHash,
                                            DeviceShareKind kind, String screenLabel, String elementJson, String body) {
        return new ShareFeedback(null, shareId, owner ? FeedbackAuthorRole.OWNER : FeedbackAuthorRole.GUEST,
                authorName, authorKeyHash, kind, screenLabel, elementJson, body,
                owner ? FeedbackStatus.ACCEPTED : FeedbackStatus.NEW, null, null);
    }

    public Long getId() { return id; }
    public Long getShareId() { return shareId; }
    public FeedbackAuthorRole getAuthorRole() { return authorRole; }
    public String getAuthorName() { return authorName; }
    public String getAuthorKeyHash() { return authorKeyHash; }
    public DeviceShareKind getKind() { return kind; }
    public String getScreenLabel() { return screenLabel; }
    public String getElementJson() { return elementJson; }
    public String getBody() { return body; }
    public FeedbackStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
