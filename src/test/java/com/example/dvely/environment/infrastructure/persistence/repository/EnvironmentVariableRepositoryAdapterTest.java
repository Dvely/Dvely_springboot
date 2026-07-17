package com.example.dvely.environment.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.environment.infrastructure.persistence.entity.EnvironmentVariableEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Review follow-up: {@code save()} must force an immediate flush on BOTH the create and the
 * update branch. Hibernate's {@code @UpdateTimestamp} is only populated as part of a flush, so a
 * plain {@code save()} on the update branch would let {@code toDomain()} read back the
 * pre-update {@code updatedAt} — the PATCH response would then show a stale timestamp even
 * though the write itself succeeded.
 */
class EnvironmentVariableRepositoryAdapterTest {

    private final SpringDataEnvironmentVariableRepository springDataRepository =
            mock(SpringDataEnvironmentVariableRepository.class);
    private final EnvironmentVariableRepositoryAdapter adapter =
            new EnvironmentVariableRepositoryAdapter(springDataRepository);

    @Test
    void savingANewVariableFlushesImmediately() {
        EnvironmentVariable newVariable = new EnvironmentVariable(11L, EnvironmentScope.PREVIEW, "KEY", "value", false);
        EnvironmentVariableEntity savedEntity = mock(EnvironmentVariableEntity.class);
        EnvironmentVariable domain = new EnvironmentVariable(
                1L, 11L, EnvironmentScope.PREVIEW, "KEY", "value", false, LocalDateTime.now(), LocalDateTime.now()
        );
        when(springDataRepository.saveAndFlush(any(EnvironmentVariableEntity.class))).thenReturn(savedEntity);
        when(savedEntity.toDomain()).thenReturn(domain);

        EnvironmentVariable result = adapter.save(newVariable);

        assertThat(result).isSameAs(domain);
        verify(springDataRepository).saveAndFlush(any(EnvironmentVariableEntity.class));
        verify(springDataRepository, never()).save(any(EnvironmentVariableEntity.class));
    }

    @Test
    void updatingAnExistingVariableAlsoFlushesImmediatelySoUpdatedAtIsFresh() {
        EnvironmentVariable existing = new EnvironmentVariable(
                1L, 11L, EnvironmentScope.PREVIEW, "KEY", "new-value", false, LocalDateTime.now(), LocalDateTime.now()
        );
        EnvironmentVariableEntity managedEntity = mock(EnvironmentVariableEntity.class);
        EnvironmentVariable refreshedDomain = new EnvironmentVariable(
                1L, 11L, EnvironmentScope.PREVIEW, "KEY", "new-value", false,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now()
        );
        when(springDataRepository.findById(1L)).thenReturn(Optional.of(managedEntity));
        when(springDataRepository.saveAndFlush(managedEntity)).thenReturn(managedEntity);
        when(managedEntity.toDomain()).thenReturn(refreshedDomain);

        EnvironmentVariable result = adapter.save(existing);

        assertThat(result).isSameAs(refreshedDomain);
        verify(managedEntity).updateFrom(existing);
        verify(springDataRepository).saveAndFlush(managedEntity);
        verify(springDataRepository, never()).save(any(EnvironmentVariableEntity.class));
    }
}
