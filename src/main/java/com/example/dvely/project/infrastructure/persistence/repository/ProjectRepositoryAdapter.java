package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.infrastructure.persistence.entity.ProjectEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProjectRepositoryAdapter implements ProjectRepository {

    private final SpringDataProjectRepository springDataProjectRepository;

    @Override
    public List<Project> findAllByOwnerUserIdAndDeletedFalseOrderByUpdatedAtDesc(Long ownerUserId) {
        return springDataProjectRepository.findByOwnerUserIdAndDeletedFalseOrderByUpdatedAtDesc(ownerUserId).stream()
                .map(ProjectEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<Project> findByIdAndOwnerUserIdAndDeletedFalse(Long projectId, Long ownerUserId) {
        return springDataProjectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .map(ProjectEntity::toDomain);
    }

    @Override
    public Optional<Project> findByIdAndOwnerUserId(Long projectId, Long ownerUserId) {
        return springDataProjectRepository.findByIdAndOwnerUserId(projectId, ownerUserId)
                .map(ProjectEntity::toDomain);
    }

    @Override
    public Optional<Project> findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
            Long ownerUserId,
            String sourceRepository
    ) {
        return springDataProjectRepository
                .findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(ownerUserId, sourceRepository)
                .map(ProjectEntity::toDomain);
    }

    @Override
    public Optional<Project> findById(Long projectId) {
        return springDataProjectRepository.findById(projectId).map(ProjectEntity::toDomain);
    }

    @Override
    public Optional<Project> findBySourceRepository(String sourceRepository) {
        return springDataProjectRepository.findFirstBySourceRepositoryIgnoreCase(sourceRepository)
                .map(ProjectEntity::toDomain);
    }

    @Override
    public Project save(Project project) {
        ProjectEntity entity;
        if (project.getId() == null) {
            entity = ProjectEntity.from(project);
        } else {
            entity = springDataProjectRepository.findById(project.getId())
                    .orElseGet(() -> ProjectEntity.from(project));
            entity.updateFrom(project);
        }

        return springDataProjectRepository.save(entity).toDomain();
    }
}
