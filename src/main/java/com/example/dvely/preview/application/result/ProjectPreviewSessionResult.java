package com.example.dvely.preview.application.result;

import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import java.time.LocalDateTime;

/**
 * 프로젝트 하나의 "현재 프리뷰" 상태.
 *
 * <p><b>프리뷰 주소를 담지 않는다</b>(#392). 예전에는 {@code previewUrl} 이 있었고 ACTIVE 일 때만
 * 채웠다 — 준비 전 주소는 게이트웨이가 열어주지 않으므로 아직 못 여는 주소를 내려주지 않으려는
 * 의도였다. 그 의도는 맞았지만 필드 자체가 함정이었다. 이름이 access 응답의 것과 같아서 읽는 쪽이
 * 계약도 같다고 가정했는데, 이 주소는 <b>접근 쿠키 없이는 열리지 않는다</b>(#77 G2) — 문서 탐색에
 * 쿠키를 요구하고 그것은 {@code POST /preview-sessions/&#123;id&#125;/access} 만 발급한다. 그래서
 * 실패가 404 가 아니라 401 로 났고, 쿠키 요구가 꺼진 dev 에서는 <b>열려서</b> 검증도 통과했다.</p>
 *
 * <p>지금 볼 수 있는지는 {@code status} 가 말하고, 열 주소는 access 가 준다. 이 결과가 주소를 들고
 * 있을 이유가 없다.</p>
 *
 * @param taskId 이 프리뷰를 만든 Agent 작업. 프로젝트 진입/버튼으로 띄운 세션은 null이다.
 */
public record ProjectPreviewSessionResult(
        String sessionId,
        Long projectId,
        String taskId,
        String status,
        LocalDateTime expiresAt,
        String failureReason
) {

    public static ProjectPreviewSessionResult from(PreviewSessionEntity session) {
        return new ProjectPreviewSessionResult(
                session.getId(),
                session.getProjectId(),
                session.getTaskId(),
                session.getStatus(),
                session.getExpiresAt(),
                session.getFailureReason()
        );
    }
}
