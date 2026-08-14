package com.example.dvely.preview.application.result;

import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import java.time.LocalDateTime;

/**
 * 프로젝트 하나의 "현재 프리뷰" 상태.
 *
 * <p>{@link #previewUrl}은 {@link PreviewSessionStatus#ACTIVE}일 때만 채워진다. 세션 행은 만들어진
 * 순간부터 URL을 들고 있지만, 준비가 끝나기 전의 그 URL은 게이트웨이가 열어주지 않아(ACTIVE 세션만
 * 프록시한다) FE가 iframe에 걸면 404만 본다 — 아직 열 수 없는 주소를 내려주지 않는 편이 화면을
 * 만드는 쪽에서 판단할 것이 적다.</p>
 *
 * @param taskId 이 프리뷰를 만든 Agent 작업. 프로젝트 진입/버튼으로 띄운 세션은 null이다.
 */
public record ProjectPreviewSessionResult(
        String sessionId,
        Long projectId,
        String taskId,
        String status,
        String previewUrl,
        LocalDateTime expiresAt,
        String failureReason
) {

    public static ProjectPreviewSessionResult from(PreviewSessionEntity session) {
        boolean servable = PreviewSessionStatus.ACTIVE.name().equals(session.getStatus());
        return new ProjectPreviewSessionResult(
                session.getId(),
                session.getProjectId(),
                session.getTaskId(),
                session.getStatus(),
                servable ? session.getPublicUrl() : null,
                session.getExpiresAt(),
                session.getFailureReason()
        );
    }
}
