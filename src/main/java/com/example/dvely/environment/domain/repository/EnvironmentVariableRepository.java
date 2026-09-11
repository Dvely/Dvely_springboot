package com.example.dvely.environment.domain.repository;

import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.value.EnvironmentScope;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface EnvironmentVariableRepository {

    EnvironmentVariable save(EnvironmentVariable variable);

    Optional<EnvironmentVariable> findByIdAndProjectId(Long id, Long projectId);

    Optional<EnvironmentVariable> findByProjectIdAndScopeAndKey(Long projectId, EnvironmentScope scope, String key);

    List<EnvironmentVariable> findByProjectIdOrderByScopeAscKeyAsc(Long projectId);

    List<EnvironmentVariable> findByProjectIdAndScopeOrderByKeyAsc(Long projectId, EnvironmentScope scope);

    /**
     * U6 6-5: 목록 조회 전용 — 값(env_value)을 읽지 않는다. {@code scope} 가 null 이면 전 스코프이고,
     * 정렬은 (scope asc, key asc) 다. {@link EnvironmentVariableSummaryView} 참고.
     */
    List<EnvironmentVariableSummaryView> findSummaries(Long projectId, EnvironmentScope scope, int limit);

    /**
     * U6 6-5: 주어진 id 중 <b>secret 이 아닌</b> 행의 평문 값만. secret 인 행은 조건에서 빠져 AES
     * 복호화가 돌지 않는다 — 비밀 평문이 메모리에 올라오는 경로 자체를 없애기 위한 분리다.
     */
    Map<Long, String> findPlainValuesByIds(Collection<Long> ids);

    void deleteById(Long id);
}
