package com.example.dvely.deviceshare.infrastructure.persistence.repository;

import com.example.dvely.deviceshare.infrastructure.persistence.entity.ShareFeedbackImagesEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataShareFeedbackImagesRepository extends JpaRepository<ShareFeedbackImagesEntity, Long> {
}
