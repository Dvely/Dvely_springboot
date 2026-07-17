package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.infrastructure.persistence.entity.ProjectEntity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
    public List<Project> findAllBySourceRepository(String sourceRepository) {
        return springDataProjectRepository.findBySourceRepositoryIgnoreCaseAndDeletedFalse(sourceRepository)
                .stream()
                .map(ProjectEntity::toDomain)
                .toList();
    }

    /**
     * I45 (issue #45): two lost-update mechanisms layered on top of each other, because a plain
     * {@code @Version} field alone only catches half the problem.
     *
     * <p><b>Case A — caller runs inside a {@code @Transactional} method</b> (e.g.
     * {@code ProjectCommandService}, {@code WebhookEventHandler.handle}, {@code deploy()}): the
     * service's initial {@code find} already loaded the entity into the persistence context, so
     * {@code findById} below is an L1-cache hit returning that same managed instance (still
     * carrying the version it was read with). {@code updateFrom} + the final {@code save} then
     * flush an {@code UPDATE ... WHERE version = ?}; Hibernate's {@code @Version} check alone is
     * enough to detect a competing commit that happened in between.</p>
     *
     * <p><b>Case B — caller has no surrounding transaction</b> (e.g.
     * {@code DeploymentCommandService.execute()}, async workers): every repository call is its
     * own auto-commit transaction, so {@code findById} here does a fresh read from the DB —
     * meaning it picks up whatever version is *currently* in the row, not the version the
     * caller's stale in-memory {@code Project} was built from. A bare {@code @Version} check
     * would then always "pass" (entity and the fresh row always agree), silently overwriting a
     * concurrent update. The explicit comparison against {@code project.getVersion()} — the
     * version the domain object actually carries from whenever *it* was read — is what catches
     * this case; {@code @Version} on its own cannot.</p>
     *
     * <p>Both branches throw the same {@link ObjectOptimisticLockingFailureException} Hibernate
     * would raise on a real flush conflict (design D2) — one exception type for callers/handlers
     * to deal with, not two.</p>
     */
    @Override
    public Project save(Project project) {
        if (project.getId() == null) {
            return springDataProjectRepository.save(ProjectEntity.from(project)).toDomain();
        }

        // D4: previously `.orElseGet(() -> ProjectEntity.from(project))` — if the row had been
        // deleted by a concurrent request between this project's read and this save, that silently
        // re-inserted it as a brand-new row (id-less entity => new PK), duplicating a project that
        // was supposed to be gone. Reusing an id that no longer exists is exactly the same "trusted
        // a stale snapshot" bug this whole fix addresses, so it gets the same treatment: OOLFE.
        ProjectEntity entity = springDataProjectRepository.findById(project.getId())
                .orElseThrow(() -> new ObjectOptimisticLockingFailureException(Project.class, project.getId()));

        // Case B guard (see class javadoc). `project.getVersion() == null` means this Project was
        // built via a legacy/fixture constructor that never captured a version — nothing to
        // compare against, so the guard is skipped and only the @Version flush check applies.
        if (project.getVersion() != null && !Objects.equals(entity.getVersion(), project.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Project.class, project.getId());
        }

        entity.updateFrom(project);
        return springDataProjectRepository.save(entity).toDomain();
    }
}
