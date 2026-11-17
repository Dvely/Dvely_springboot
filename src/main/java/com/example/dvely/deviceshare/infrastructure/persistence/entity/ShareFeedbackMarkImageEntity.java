package com.example.dvely.deviceshare.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** JPA row for {@code device_share_feedback_mark_images}. ②부터 짚은 곳마다 잘라 낸 그림(설계 §15.10). */
@Entity
@Table(name = "device_share_feedback_mark_images")
@IdClass(ShareFeedbackMarkImageEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShareFeedbackMarkImageEntity {

    @Id
    @Column(name = "feedback_id")
    private Long feedbackId;

    @Id
    @Column(name = "mark_no")
    private Integer markNo;

    @Lob
    @Column(name = "crop", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] crop;

    public ShareFeedbackMarkImageEntity(Long feedbackId, Integer markNo, byte[] crop) {
        this.feedbackId = feedbackId;
        this.markNo = markNo;
        this.crop = crop;
    }

    /** 복합 키. */
    @Getter
    @NoArgsConstructor
    public static class Key implements Serializable {

        private Long feedbackId;
        private Integer markNo;

        public Key(Long feedbackId, Integer markNo) {
            this.feedbackId = feedbackId;
            this.markNo = markNo;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && java.util.Objects.equals(feedbackId, k.feedbackId)
                    && java.util.Objects.equals(markNo, k.markNo);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(feedbackId, markNo);
        }
    }
}
