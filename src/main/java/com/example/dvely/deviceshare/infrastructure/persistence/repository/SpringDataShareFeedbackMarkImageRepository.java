package com.example.dvely.deviceshare.infrastructure.persistence.repository;

import com.example.dvely.deviceshare.infrastructure.persistence.entity.ShareFeedbackMarkImageEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataShareFeedbackMarkImageRepository
        extends JpaRepository<ShareFeedbackMarkImageEntity, ShareFeedbackMarkImageEntity.Key> {

    /** 그림이 있는 짚은 곳 번호. 그림 바이트는 읽지 않는다 */
    @Query("select m.markNo from ShareFeedbackMarkImageEntity m where m.feedbackId = :id order by m.markNo")
    List<Integer> markNumbers(@Param("id") Long feedbackId);
}
