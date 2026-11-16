package com.example.dvely.deviceshare.infrastructure.persistence.repository;

import com.example.dvely.deviceshare.infrastructure.persistence.entity.ShareFeedbackNoteEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataShareFeedbackNoteRepository extends JpaRepository<ShareFeedbackNoteEntity, Long> {

    @Query("select n from ShareFeedbackNoteEntity n where n.feedbackId = :feedbackId order by n.id")
    List<ShareFeedbackNoteEntity> findByFeedback(@Param("feedbackId") Long feedbackId);
}
