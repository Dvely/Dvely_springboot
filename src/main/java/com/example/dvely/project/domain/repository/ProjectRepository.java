package com.example.dvely.project.domain.repository;

import com.example.dvely.project.domain.model.Project;
import java.util.Collection;
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

    /**
     * U6(#341) 6-7: 여러 프로젝트를 한 번에. 삭제 여부를 가리지 않는다 — 휴지통 목록은 "원본이
     * 살아 있나 / 삭제됐나" 를 구분해야 해서 둘 다 필요하고, 예전에는 그것 때문에 대화마다
     * 조회를 두 번씩 돌렸다.
     */
    List<Project> findAllByIdInAndOwnerUserId(Collection<Long> projectIds, Long ownerUserId);

    /**
     * U6(#341) 6-7: 주어진 저장소들을 쓰는 활성 프로젝트를 최신순으로 한 번에. 저장소별 첫 건이
     * {@link #findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc}
     * 가 돌려주던 것과 같다(대소문자 무시 근거도 그쪽 javadoc 과 동일). updated_at 이 DATETIME(초)
     * 라 같은 초의 행 순서가 비결정적이므로 id 를 tiebreaker 로 붙여 고정했다.
     */
    List<Project> findAllActiveByOwnerUserIdAndSourceRepositoryIn(
            Long ownerUserId,
            Collection<String> sourceRepositories
    );

    Optional<Project> findById(Long projectId);

    Optional<Project> findBySourceRepository(String sourceRepository);

    List<Project> findAllBySourceRepository(String sourceRepository);

    Project save(Project project);
}
