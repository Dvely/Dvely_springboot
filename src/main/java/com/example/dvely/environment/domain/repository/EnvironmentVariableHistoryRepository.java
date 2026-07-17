package com.example.dvely.environment.domain.repository;

import com.example.dvely.environment.domain.model.EnvironmentVariableHistory;
import java.util.List;

public interface EnvironmentVariableHistoryRepository {

    EnvironmentVariableHistory save(EnvironmentVariableHistory history);

    /** Most recent first (created_at desc, id desc as a tiebreaker for same-timestamp events). */
    List<EnvironmentVariableHistory> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId, int limit);
}
