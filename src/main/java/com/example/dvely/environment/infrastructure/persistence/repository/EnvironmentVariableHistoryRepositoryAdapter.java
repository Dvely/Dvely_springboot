package com.example.dvely.environment.infrastructure.persistence.repository;

import com.example.dvely.environment.domain.model.EnvironmentVariableHistory;
import com.example.dvely.environment.domain.repository.EnvironmentVariableHistoryRepository;
import com.example.dvely.environment.infrastructure.persistence.entity.EnvironmentVariableHistoryEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class EnvironmentVariableHistoryRepositoryAdapter implements EnvironmentVariableHistoryRepository {

    private final SpringDataEnvironmentVariableHistoryRepository springDataRepository;

    @Override
    public EnvironmentVariableHistory save(EnvironmentVariableHistory history) {
        return springDataRepository.save(EnvironmentVariableHistoryEntity.from(history)).toDomain();
    }

    @Override
    public List<EnvironmentVariableHistory> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId, int limit) {
        return springDataRepository
                .findByProjectIdOrderByCreatedAtDescIdDesc(projectId, PageRequest.of(0, limit))
                .stream()
                .map(EnvironmentVariableHistoryEntity::toDomain)
                .toList();
    }
}
