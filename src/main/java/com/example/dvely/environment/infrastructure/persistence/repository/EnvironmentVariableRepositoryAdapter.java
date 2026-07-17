package com.example.dvely.environment.infrastructure.persistence.repository;

import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import com.example.dvely.environment.infrastructure.persistence.entity.EnvironmentVariableEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
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
        return springDataRepository.save(entity).toDomain();
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
    public void deleteById(Long id) {
        springDataRepository.deleteById(id);
    }
}
