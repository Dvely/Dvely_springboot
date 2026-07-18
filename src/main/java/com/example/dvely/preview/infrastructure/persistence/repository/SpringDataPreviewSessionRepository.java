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

    // Additive for Cloud Ops Agent (EPIC 15, design D8): resolves the RESTART/STATUS_CHECK target
    // by project rather than by taskId — those chat requests don't carry the originating CODE/DEPLOY
    // task, only a projectId. ownerUserId stays in the filter for the same defensive-ownership
    // reason every other finder here has it (agent execution context is not exempt).
    Optional<PreviewSessionEntity> findFirstByProjectIdAndOwnerUserIdAndStatusOrderByLastAccessedAtDesc(
            Long projectId, Long ownerUserId, String status);
}
