package com.example.dvely.deployment.infrastructure.persistence.repository;

import com.example.dvely.deployment.infrastructure.persistence.entity.DeploymentHistoryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataDeploymentHistoryRepository extends JpaRepository<DeploymentHistoryEntity, Long> {

    List<DeploymentHistoryEntity> findByProjectIdOrderByTriggeredAtDesc(Long projectId);

    List<DeploymentHistoryEntity> findByProjectIdAndStatus(Long projectId, String status);

    @Query("SELECT h FROM DeploymentHistoryEntity h WHERE h.projectId = :projectId AND h.status = 'IN_PROGRESS' ORDER BY h.triggeredAt DESC LIMIT 1")
    Optional<DeploymentHistoryEntity> findLatestInProgressByProjectId(@Param("projectId") Long projectId);
}
