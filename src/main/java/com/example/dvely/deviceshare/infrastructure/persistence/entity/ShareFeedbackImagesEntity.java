package com.example.dvely.deviceshare.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** JPA row for {@code device_share_feedback_images}. 목록이 읽지 않도록 피드백과 나눴다. */
@Entity
@Table(name = "device_share_feedback_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShareFeedbackImagesEntity {

    @Id
    @Column(name = "feedback_id")
    private Long feedbackId;

    @Lob
    @Column(name = "crop", columnDefinition = "MEDIUMBLOB")
    private byte[] crop;

    @Lob
    @Column(name = "screen", columnDefinition = "MEDIUMBLOB")
    private byte[] screen;

    /** 짚은 곳마다 테두리와 번호를 그린 화면(여러 곳일 때, 설계 §15.10) */
    @Lob
    @Column(name = "annotated", columnDefinition = "MEDIUMBLOB")
    private byte[] annotated;

    public ShareFeedbackImagesEntity(Long feedbackId, byte[] crop, byte[] screen, byte[] annotated) {
        this.feedbackId = feedbackId;
        this.crop = crop;
        this.screen = screen;
        this.annotated = annotated;
    }
}
