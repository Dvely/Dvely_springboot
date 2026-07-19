package com.example.dvely.environment.infrastructure.persistence.repository;

import com.example.dvely.environment.infrastructure.persistence.entity.EnvironmentVariableEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataEnvironmentVariableRepository extends JpaRepository<EnvironmentVariableEntity, Long> {

    Optional<EnvironmentVariableEntity> findByIdAndProjectId(Long id, Long projectId);

    Optional<EnvironmentVariableEntity> findByProjectIdAndScopeAndKey(Long projectId, String scope, String key);

    List<EnvironmentVariableEntity> findByProjectIdOrderByScopeAscKeyAsc(Long projectId);

    List<EnvironmentVariableEntity> findByProjectIdAndScopeOrderByKeyAsc(Long projectId, String scope);
}
