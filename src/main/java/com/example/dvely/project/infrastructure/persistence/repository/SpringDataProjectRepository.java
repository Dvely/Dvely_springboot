package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.infrastructure.persistence.entity.ProjectEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * source_repository 로 찾는 세 메서드에 {@code IgnoreCase} 가 없는 것은 실수가 아니다(#338).
 *
 * <p>{@code IgnoreCase} 는 {@code upper(source_repository) = upper(?)} 를 만든다. 컬럼에 함수를
 * 씌우면 인덱스를 못 타므로 이 셋은 전부 projects 풀스캔이었다 — 그리고 이 셋은 모든 GitHub
 * 웹훅(push · pull_request · workflow_run)이 지나는 길이다.</p>
 *
 * <p>대소문자 무시는 그대로 유지된다. {@code projects.source_repository} 의 컬레이션이
 * {@code utf8mb4_unicode_ci} 라 비교 자체가 이미 대소문자를 구분하지 않기 때문이다. 즉
 * {@code IgnoreCase} 는 동작을 더해주지 않으면서 인덱스만 죽이고 있었다. 컬레이션을
 * {@code _bin} 이나 {@code _cs} 로 바꾸면 그때는 동작이 바뀐다 — 그 변경을 하려면 여기부터 본다.</p>
 */
public interface SpringDataProjectRepository extends JpaRepository<ProjectEntity, Long> {

    List<ProjectEntity> findByOwnerUserIdAndDeletedFalseOrderByUpdatedAtDesc(Long ownerUserId);

    Optional<ProjectEntity> findByIdAndOwnerUserIdAndDeletedFalse(Long projectId, Long ownerUserId);

    Optional<ProjectEntity> findByIdAndOwnerUserId(Long projectId, Long ownerUserId);

    Optional<ProjectEntity> findFirstByOwnerUserIdAndSourceRepositoryAndDeletedFalseOrderByUpdatedAtDesc(
            Long ownerUserId,
            String sourceRepository
    );

    Optional<ProjectEntity> findFirstBySourceRepository(String sourceRepository);

    List<ProjectEntity> findBySourceRepositoryAndDeletedFalse(String sourceRepository);
}
