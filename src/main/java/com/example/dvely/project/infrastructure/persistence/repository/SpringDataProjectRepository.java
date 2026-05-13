package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.infrastructure.persistence.entity.ProjectEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProjectRepository extends JpaRepository<ProjectEntity, Long> {

    List<ProjectEntity> findByOwnerUserIdAndDeletedFalseOrderByUpdatedAtDesc(Long ownerUserId);

    Optional<ProjectEntity> findByIdAndOwnerUserIdAndDeletedFalse(Long projectId, Long ownerUserId);

    Optional<ProjectEntity> findByIdAndOwnerUserId(Long projectId, Long ownerUserId);

    Optional<ProjectEntity> findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
            Long ownerUserId,
            String sourceRepository
    );

    Optional<ProjectEntity> findFirstBySourceRepositoryIgnoreCase(String sourceRepository);
}
