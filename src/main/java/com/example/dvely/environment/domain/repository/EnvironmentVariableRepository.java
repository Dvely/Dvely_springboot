package com.example.dvely.environment.domain.repository;

import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import java.util.List;
import java.util.Optional;

public interface EnvironmentVariableRepository {

    EnvironmentVariable save(EnvironmentVariable variable);

    Optional<EnvironmentVariable> findByIdAndProjectId(Long id, Long projectId);

    Optional<EnvironmentVariable> findByProjectIdAndScopeAndKey(Long projectId, EnvironmentScope scope, String key);

    List<EnvironmentVariable> findByProjectIdOrderByScopeAscKeyAsc(Long projectId);

    List<EnvironmentVariable> findByProjectIdAndScopeOrderByKeyAsc(Long projectId, EnvironmentScope scope);

    void deleteById(Long id);
}
