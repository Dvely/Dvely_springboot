package com.example.dvely.preview.infrastructure.persistence.repository;

import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPreviewSessionRepository extends JpaRepository<PreviewSessionEntity, String> {

    Optional<PreviewSessionEntity> findByTaskIdAndStatus(String taskId, String status);

    Optional<PreviewSessionEntity> findByIdAndAccessTokenAndStatus(
            String id,
            String accessToken,
            String status
    );

    Optional<PreviewSessionEntity> findByIdAndOwnerUserId(String id, Long ownerUserId);

    List<PreviewSessionEntity> findByOwnerUserIdAndStatus(Long ownerUserId, String status);

    List<PreviewSessionEntity> findByStatusAndExpiresAtBefore(String status, LocalDateTime expiresAt);
}
