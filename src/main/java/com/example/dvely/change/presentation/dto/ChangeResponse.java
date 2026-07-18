package com.example.dvely.change.presentation.dto;

import com.example.dvely.change.application.result.ChangeResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Agent CODE 작업 1건의 코드 변경 결과. CODE 작업이 성공할 때마다 1건씩 생성됩니다.")
public record ChangeResponse(
        @Schema(description = "Change ID") Long changeId,
        @Schema(description = "프로젝트 ID") Long projectId,
        @Schema(description = "변경을 발생시킨 대화 ID") Long conversationId,
        @Schema(description = "변경을 생성한 Agent task ID") String taskId,
        @Schema(description = "변경 결과를 미리 볼 수 있는 Preview 세션 ID. 세션이 만료/종료됐을 수 있음", nullable = true) String previewSessionId,

        @Schema(description = "변경 상태. PREVIEW_READY: preview까지만 반영, DEPLOYED: 실제 배포에 포함됨", allowableValues = {"PREVIEW_READY", "DEPLOYED"}, example = "PREVIEW_READY")
        String status,

        @Schema(description = "변경 내용 요약") String summary,
        @Schema(description = "생성 시각") LocalDateTime createdAt,
        @Schema(description = "마지막 수정 시각(DEPLOYED로 전이된 시각 등)") LocalDateTime updatedAt
) {
    public static ChangeResponse from(ChangeResult result) {
        return new ChangeResponse(
                result.changeId(),
                result.projectId(),
                result.conversationId(),
                result.taskId(),
                result.previewSessionId(),
                result.status(),
                result.summary(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}
