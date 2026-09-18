package com.example.dvely.environment.infrastructure.persistence.repository;

import com.example.dvely.environment.domain.repository.EnvironmentVariableSummaryView;
import com.example.dvely.environment.infrastructure.persistence.entity.EnvironmentVariableEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataEnvironmentVariableRepository extends JpaRepository<EnvironmentVariableEntity, Long> {

    Optional<EnvironmentVariableEntity> findByIdAndProjectId(Long id, Long projectId);

    Optional<EnvironmentVariableEntity> findByProjectIdAndScopeAndKey(Long projectId, String scope, String key);

    List<EnvironmentVariableEntity> findByProjectIdOrderByScopeAscKeyAsc(Long projectId);

    List<EnvironmentVariableEntity> findByProjectIdAndScopeOrderByKeyAsc(Long projectId, String scope);

    // U6 6-5: 목록은 env_value 를 읽지 않는다. scope 가 null 이면 전체 스코프.
    @Query("""
            select new com.example.dvely.environment.domain.repository.EnvironmentVariableSummaryView(
                       e.id, e.scope, e.key, e.secret, e.createdAt, e.updatedAt)
            from EnvironmentVariableEntity e
            where e.projectId = :projectId
              and (:scope is null or e.scope = :scope)
            order by e.scope asc, e.key asc
            """)
    List<EnvironmentVariableSummaryView> findSummaries(
            @Param("projectId") Long projectId, @Param("scope") String scope, Pageable pageable);

    // U6 6-5: 응답에 실제로 실리는 평문만. secret 인 행은 조건에서 빠지므로 AES 복호화가 아예 돌지
    // 않는다 — 목록 페이지에 든 id 로만 좁혀, 페이지 밖 행의 값도 읽지 않는다.
    @Query("""
            select e.id, e.value
            from EnvironmentVariableEntity e
            where e.id in :ids
              and e.secret = false
            """)
    List<Object[]> findPlainValuesByIds(@Param("ids") Collection<Long> ids);
}
