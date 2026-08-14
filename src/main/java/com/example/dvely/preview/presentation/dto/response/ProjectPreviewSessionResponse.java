package com.example.dvely.preview.presentation.dto.response;

import com.example.dvely.preview.application.result.ProjectPreviewSessionResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

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

        @Schema(description = "프리뷰 주소. status=ACTIVE 일 때만 값이 있고, 그 외에는 null "
                + "(준비가 끝나기 전 주소는 게이트웨이가 404로 응답한다)",
                nullable = true, example = "https://qeploy.com/api/v1/previews/3f1a.../abcdef.../")
        String previewUrl,

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
                result.previewUrl(),
                result.expiresAt(),
                result.failureReason()
        );
    }
}
