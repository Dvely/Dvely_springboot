package com.example.dvely.project.domain.repository;

import com.example.dvely.project.domain.model.Project;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository {

    List<Project> findAllByOwnerUserIdAndDeletedFalseOrderByUpdatedAtDesc(Long ownerUserId);

    Optional<Project> findByIdAndOwnerUserIdAndDeletedFalse(Long projectId, Long ownerUserId);

    Optional<Project> findByIdAndOwnerUserId(Long projectId, Long ownerUserId);

    /**
     * 이름의 {@code IgnoreCase} 는 계약("대소문자를 구분하지 않고 찾는다")이지 구현 수단이 아니다.
     * 그 대소문자 무시는 {@code projects.source_repository} 의 {@code utf8mb4_unicode_ci} 컬레이션이
     * 제공한다 — 쿼리에서 {@code upper()} 를 씌우던 방식은 인덱스를 죽여서 걷어냈다
     * (#338, {@code SpringDataProjectRepository} 의 javadoc 참조). 호출부가 볼 동작은 같다.
     */
    Optional<Project> findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
            Long ownerUserId,
            String sourceRepository
    );

    Optional<Project> findById(Long projectId);

    Optional<Project> findBySourceRepository(String sourceRepository);

    List<Project> findAllBySourceRepository(String sourceRepository);

    Project save(Project project);
}
