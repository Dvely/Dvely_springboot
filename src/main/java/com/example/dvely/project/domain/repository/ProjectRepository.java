package com.example.dvely.project.domain.repository;

import com.example.dvely.project.domain.model.Project;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository {

    List<Project> findAllByOwnerUserIdAndDeletedFalseOrderByUpdatedAtDesc(Long ownerUserId);

    Optional<Project> findByIdAndOwnerUserIdAndDeletedFalse(Long projectId, Long ownerUserId);

    Optional<Project> findByIdAndOwnerUserId(Long projectId, Long ownerUserId);

    Optional<Project> findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
            Long ownerUserId,
            String sourceRepository
    );

    Optional<Project> findById(Long projectId);

    Optional<Project> findBySourceRepository(String sourceRepository);

    List<Project> findAllBySourceRepository(String sourceRepository);

    Project save(Project project);
}
