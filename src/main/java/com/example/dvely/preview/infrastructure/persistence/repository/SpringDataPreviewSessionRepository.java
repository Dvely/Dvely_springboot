package com.example.dvely.preview.infrastructure.persistence.repository;

import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataPreviewSessionRepository extends JpaRepository<PreviewSessionEntity, String> {

    Optional<PreviewSessionEntity> findByTaskIdAndStatus(String taskId, String status);

    Optional<PreviewSessionEntity> findByIdAndAccessTokenAndStatus(
            String id,
            String accessToken,
            String status
    );

    Optional<PreviewSessionEntity> findByIdAndOwnerUserId(String id, Long ownerUserId);

    List<PreviewSessionEntity> findByOwnerUserIdAndStatus(Long ownerUserId, String status);

    // 만료 청소 대상은 두 종류다: TTL 이 지난 ACTIVE 세션과, 준비 도중 앱이 죽어 어느 쪽으로도
    // 가지 못한 채 남은 PROVISIONING 세션. 상태별로 종료 상태가 다르므로(EXPIRED / FAILED) 한
    // 번에 읽어 호출부에서 갈라 쓴다.
    List<PreviewSessionEntity> findByStatusInAndExpiresAtBefore(
            Collection<String> statuses, LocalDateTime expiresAt);

    // Additive for Cloud Ops Agent (EPIC 15, design D8): resolves the RESTART/STATUS_CHECK target
    // by project rather than by taskId — those chat requests don't carry the originating CODE/DEPLOY
    // task, only a projectId. ownerUserId stays in the filter for the same defensive-ownership
    // reason every other finder here has it (agent execution context is not exempt).
    Optional<PreviewSessionEntity> findFirstByProjectIdAndOwnerUserIdAndStatusOrderByLastAccessedAtDesc(
            Long projectId, Long ownerUserId, String status);

    // 프로젝트 단위 프리뷰(#진입 시 조회 / 버튼 프로비저닝)용. 위의 단일-status 조회와 달리 여러
    // 상태를 한 번에 본다: FE 는 "떠 있음(ACTIVE)"뿐 아니라 "준비 중(PROVISIONING)"과 "실패했음
    // (FAILED)"도 각각 다른 화면으로 보여줘야 하고, 그 셋 중 무엇이 현재 상태인지는 가장 최근에
    // 손댄 행 하나로 결정된다.
    Optional<PreviewSessionEntity> findFirstByProjectIdAndOwnerUserIdAndStatusInOrderByLastAccessedAtDesc(
            Long projectId, Long ownerUserId, Collection<String> statuses);

    // 동시 요청(버튼 더블클릭 등) 중재용 — 살아 있는 세션 전부를 읽어 createdAt 이 가장 이른
    // 하나만 남기고 나머지는 스스로 물러난다. ProjectPreviewService#provision 참고.
    List<PreviewSessionEntity> findByProjectIdAndOwnerUserIdAndStatusIn(
            Long projectId, Long ownerUserId, Collection<String> statuses);

    /**
     * 게이트웨이 접근 흔적만 갱신하는 단일 UPDATE (Issue #342, 7-1).
     *
     * <p>엔티티를 고쳐 {@code save} 하는 대신 UPDATE 한 문장을 쓰는 이유는 <b>호출 빈도</b>다. 이
     * 갱신은 프리뷰 페이지가 끌어오는 자산 하나하나마다 불린다 — 엔티티 경로는 그 요청마다 더티 체크
     * UPDATE 와 쓰기 락을 만들었다.</p>
     *
     * <p>{@code lastAccessedAt < :staleBefore} 조건이 <b>스로틀 자체</b>다. 호출부도 같은 조건을
     * 미리 보고 대부분을 걸러내지만, 같은 페이지의 자산 요청 여럿이 동시에 같은 낡은 행을 읽었을 때는
     * 그 확인이 전부 통과한다. MySQL 의 UPDATE 는 현재 커밋 값을 다시 읽으므로 그 중 먼저 커밋한
     * 하나만 조건에 맞고 나머지는 0 행으로 끝난다.</p>
     *
     * <p>{@code expiresAt} 은 호출부가 이미 정한 값을 그대로 받는다 — 유예({@code
     * holdForBindingApproval})가 준 더 먼 만료를 앞당기지 않기 위한 비교는 호출부에 남는다.
     * {@code updatedAt} 은 명시적으로 넣는다: 벌크 UPDATE 는 {@code @UpdateTimestamp} 를 거치지
     * 않으므로 안 넣으면 이 경로에서만 갱신 시각이 멈춘다.</p>
     */
    @Modifying(flushAutomatically = false, clearAutomatically = false)
    @Query("update PreviewSessionEntity s "
           + "set s.lastAccessedAt = :now, s.updatedAt = :now, s.expiresAt = :expiresAt "
           + "where s.id = :sessionId and s.lastAccessedAt < :staleBefore")
    int touchAccess(@Param("sessionId") String sessionId,
                    @Param("now") LocalDateTime now,
                    @Param("expiresAt") LocalDateTime expiresAt,
                    @Param("staleBefore") LocalDateTime staleBefore);
}
