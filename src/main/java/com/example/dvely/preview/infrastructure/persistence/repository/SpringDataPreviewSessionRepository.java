package com.example.dvely.preview.infrastructure.persistence.repository;

import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
