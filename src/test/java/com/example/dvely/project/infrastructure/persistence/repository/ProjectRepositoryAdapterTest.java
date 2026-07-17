package com.example.dvely.project.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import com.example.dvely.project.infrastructure.persistence.entity.ProjectEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * I45 (#45): {@link ProjectRepositoryAdapter#save} carries the adapter-level version guard that
 * protects the "no surrounding transaction" write path (see the method's own javadoc for the
 * full case A/B analysis) — this suite exercises that guard directly with a mocked
 * {@link ProjectEntity}, since it's the one piece of the two-layer lock that isn't already
 * covered by Hibernate's own {@code @Version} flush behavior.
 */
class ProjectRepositoryAdapterTest {

    private final SpringDataProjectRepository springDataProjectRepository = mock(SpringDataProjectRepository.class);
    private final ProjectRepositoryAdapter adapter = new ProjectRepositoryAdapter(springDataProjectRepository);

    @Test
    void throwsOptimisticLockingFailureWhenDomainVersionDiffersFromStoredVersion() {
        Project stale = project(11L, 3L); // read a while ago at version 3
        ProjectEntity current = mock(ProjectEntity.class);
        when(current.getVersion()).thenReturn(5L); // someone else has since saved twice
        when(springDataProjectRepository.findById(11L)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> adapter.save(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(current, never()).updateFrom(any());
        verify(springDataProjectRepository, never()).save(any(ProjectEntity.class));
    }

    @Test
    void savesWhenDomainVersionMatchesStoredVersion() {
        Project current = project(11L, 3L);
        ProjectEntity entity = mock(ProjectEntity.class);
        when(entity.getVersion()).thenReturn(3L);
        when(springDataProjectRepository.findById(11L)).thenReturn(Optional.of(entity));
        when(springDataProjectRepository.save(entity)).thenReturn(entity);
        Project afterSave = project(11L, 4L); // Hibernate would have bumped this on flush
        when(entity.toDomain()).thenReturn(afterSave);

        Project result = adapter.save(current);

        assertThat(result).isSameAs(afterSave);
        verify(entity).updateFrom(current);
        verify(springDataProjectRepository).save(entity);
    }

    @Test
    void skipsTheVersionGuardWhenDomainVersionIsNull() {
        // A Project built via a legacy/fixture constructor that never captured a version has
        // nothing to compare against — the guard must not fabricate a mismatch out of null.
        Project noVersionCaptured = new Project(
                11L, 1L, "my-project", ProjectStatus.ACTIVE, "blank", null, "fast",
                DeployStatus.DRAFT, null, null, null, null,
                RepositoryVisibility.PRIVATE, RepositoryBindingStatus.NOT_BOUND, RepositoryHealthStatus.UNKNOWN_ERROR,
                false, LocalDateTime.now(), LocalDateTime.now()
        );
        assertThat(noVersionCaptured.getVersion()).isNull();

        ProjectEntity entity = mock(ProjectEntity.class);
        when(entity.getVersion()).thenReturn(99L); // arbitrary — must not matter
        when(springDataProjectRepository.findById(11L)).thenReturn(Optional.of(entity));
        when(springDataProjectRepository.save(entity)).thenReturn(entity);
        Project saved = project(11L, 100L);
        when(entity.toDomain()).thenReturn(saved);

        Project result = adapter.save(noVersionCaptured);

        assertThat(result).isSameAs(saved);
        verify(entity).updateFrom(noVersionCaptured);
    }

    @Test
    void throwsOptimisticLockingFailureInsteadOfReinsertingWhenRowWasDeletedConcurrently() {
        // D4: the previous `.orElseGet(() -> ProjectEntity.from(project))` behavior would have
        // silently created a brand-new row here (id-less entity => new PK) instead of surfacing
        // the fact that the row this Project thought it was updating no longer exists.
        Project deletedConcurrently = project(11L, 3L);
        when(springDataProjectRepository.findById(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.save(deletedConcurrently))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(springDataProjectRepository, never()).save(any(ProjectEntity.class));
    }

    @Test
    void insertsANewProjectWhenIdIsNull() {
        Project newProject = new Project(1L, "my-project", "blank", null, "fast", RepositoryVisibility.PRIVATE);
        assertThat(newProject.getId()).isNull();
        ProjectEntity insertedEntity = mock(ProjectEntity.class);
        when(springDataProjectRepository.save(any(ProjectEntity.class))).thenReturn(insertedEntity);
        when(insertedEntity.toDomain()).thenReturn(newProject);

        adapter.save(newProject);

        verify(springDataProjectRepository, never()).findById(any());
        verify(springDataProjectRepository).save(any(ProjectEntity.class));
    }

    private Project project(Long id, Long version) {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                id, 1L, "my-project", ProjectStatus.ACTIVE, "blank", null, "fast",
                DeployStatus.DRAFT, null, null, "octo/repo", "octo/repo",
                RepositoryVisibility.PUBLIC, RepositoryBindingStatus.BOUND, RepositoryHealthStatus.HEALTHY,
                null, null, null, null, null, null, null, null,
                false, now, now, version
        );
    }
}
