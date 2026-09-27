package com.example.dvely.preview.presentation.dto.response;

import com.example.dvely.preview.application.result.ProjectPreviewSessionResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 프로젝트의 현재 프리뷰 세션.
 *
 * <p><b>프리뷰 주소는 여기에 없다</b>(#392). 예전에는 {@code previewUrl} 이 있었는데 이름이 access
 * 응답의 것과 같아서, 읽는 쪽이 계약도 같다고 가정했다. 이 값은 회전과 함께 갱신되므로 낡지는
 * 않았지만 <b>쿠키 없이는 열리지 않았다</b> — 문서 탐색에는 접근 쿠키가 필요하고(#77 G2) 그것은
 * {@code POST /preview-sessions/&#123;id&#125;/access} 만 발급한다. 그래서 실패가 404 가 아니라
 * 401 로 났다.</p>
 *
 * <p>지금 볼 수 있는지는 {@code status} 가 말하고, 열 주소는 access 가 준다. 이 응답이 주소를 들고
 * 있을 이유가 없다.</p>
 */
@Schema(description = "프로젝트의 현재 프리뷰 세션")
public record ProjectPreviewSessionResponse(

        @Schema(description = "Preview 세션 ID. 상태/로그 조회(/api/v1/preview-sessions/{id}/...)에 사용", example = "3f1a...")
        String sessionId,

        @Schema(description = "프로젝트 ID", example = "101")
        Long projectId,

        @Schema(description = "이 프리뷰를 만든 Agent 작업 ID. 프로젝트 단위로 띄운 프리뷰는 null",
                nullable = true, example = "d4e5f6a1b2c3")
        String taskId,

        @Schema(description = "ACTIVE(볼 수 있음) | PROVISIONING(준비 중) | FAILED(준비 실패)",
                example = "PROVISIONING")
        String status,

        @Schema(description = "이 시각이 지나면 컨테이너가 회수된다. 프리뷰를 열어두고 보는 동안에는 접근할 때마다 연장된다")
        LocalDateTime expiresAt,

        @Schema(description = "status=FAILED 일 때의 실패 사유(빌드 로그 꼬리 포함). 그 외에는 null",
                nullable = true)
        String failureReason
) {

    public static ProjectPreviewSessionResponse from(ProjectPreviewSessionResult result) {
        return new ProjectPreviewSessionResponse(
                result.sessionId(),
                result.projectId(),
                result.taskId(),
                result.status(),
                result.expiresAt(),
                result.failureReason()
        );
    }
}
