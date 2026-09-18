package com.example.dvely.environment.infrastructure.persistence.repository;

import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.environment.domain.repository.EnvironmentVariableSummaryView;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.environment.infrastructure.persistence.entity.EnvironmentVariableEntity;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class EnvironmentVariableRepositoryAdapter implements EnvironmentVariableRepository {

    private final SpringDataEnvironmentVariableRepository springDataRepository;

    @Override
    public EnvironmentVariable save(EnvironmentVariable variable) {
        if (variable.getId() == null) {
            // saveAndFlush forces the INSERT (and its unique-constraint check) to happen
            // synchronously here rather than at transaction commit — otherwise a concurrent
            // duplicate-key race would surface as a DataIntegrityViolationException only after
            // EnvironmentVariableCommandService.create() has already returned, where it can no
            // longer be caught and translated to the 409 IllegalStateException (see design §3.6).
            return springDataRepository.saveAndFlush(EnvironmentVariableEntity.from(variable)).toDomain();
        }
        EnvironmentVariableEntity entity = springDataRepository.findById(variable.getId())
                .orElseThrow(() -> new IllegalStateException("환경변수를 찾을 수 없습니다. id=" + variable.getId()));
        entity.updateFrom(variable);
        // saveAndFlush here too: a plain save() only schedules the UPDATE for the next flush
        // (typically at transaction commit), and Hibernate's @UpdateTimestamp is populated as
        // part of that flush — so without forcing it now, toDomain() below would read back the
        // *pre-update* updatedAt, and the PATCH response would show a stale timestamp even
        // though the write itself succeeded (review finding: stale updatedAt in PATCH response).
        return springDataRepository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<EnvironmentVariable> findByIdAndProjectId(Long id, Long projectId) {
        return springDataRepository.findByIdAndProjectId(id, projectId).map(EnvironmentVariableEntity::toDomain);
    }

    @Override
    public Optional<EnvironmentVariable> findByProjectIdAndScopeAndKey(Long projectId, EnvironmentScope scope, String key) {
        return springDataRepository.findByProjectIdAndScopeAndKey(projectId, scope.name(), key)
                .map(EnvironmentVariableEntity::toDomain);
    }

    @Override
    public List<EnvironmentVariable> findByProjectIdOrderByScopeAscKeyAsc(Long projectId) {
        return springDataRepository.findByProjectIdOrderByScopeAscKeyAsc(projectId)
                .stream()
                .map(EnvironmentVariableEntity::toDomain)
                .toList();
    }

    @Override
    public List<EnvironmentVariable> findByProjectIdAndScopeOrderByKeyAsc(Long projectId, EnvironmentScope scope) {
        return springDataRepository.findByProjectIdAndScopeOrderByKeyAsc(projectId, scope.name())
                .stream()
                .map(EnvironmentVariableEntity::toDomain)
                .toList();
    }

    @Override
    public List<EnvironmentVariableSummaryView> findSummaries(Long projectId, EnvironmentScope scope, int limit) {
        return springDataRepository.findSummaries(
                projectId, scope == null ? null : scope.name(), PageRequest.of(0, limit));
    }

    @Override
    public Map<Long, String> findPlainValuesByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> values = new LinkedHashMap<>();
        for (Object[] row : springDataRepository.findPlainValuesByIds(ids)) {
            // 값이 NULL 인 행은 스킵한다 — Map#put 은 null 을 담지만 "값이 없다" 와 구분이 안 된다.
            if (row[1] != null) {
                values.put((Long) row[0], (String) row[1]);
            }
        }
        return values;
    }

    @Override
    public void deleteById(Long id) {
        springDataRepository.deleteById(id);
    }
}
