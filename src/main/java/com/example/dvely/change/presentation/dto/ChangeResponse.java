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

        @Schema(description = "변경 상태. PREVIEW_READY: preview까지만 반영(결과 승인 대기 또는 게이트 미발동), " +
                "MERGED: 결과 승인 후 main에 반영됨, REJECTED: 결과 승인 거절로 main 미반영(preview에만 잔존), " +
                "DEPLOYED: 실제 배포에 포함됨",
                allowableValues = {"PREVIEW_READY", "MERGED", "REJECTED", "DEPLOYED"}, example = "PREVIEW_READY")
        String status,

        @Schema(description = "변경 내용 요약") String summary,
        @Schema(description = "이 변경을 MERGED/REJECTED로 결정한 RESULT 승인 ID. 결과 승인 게이트가 발동하지 않은(legacy) 변경은 null", nullable = true)
        Long approvalId,
        @Schema(description = "preview→main 반영 PR 번호. 신규 커밋이 없어 PR 없이 반영 처리된 경우 null", nullable = true)
        Integer prNumber,
        @Schema(description = "main 반영 커밋 SHA. MERGED 상태에서만 값이 있음", nullable = true)
        String mergeCommitSha,
        @Schema(description = "main 반영(MERGED로 전이된) 시각", nullable = true)
        LocalDateTime mergedAt,
        @Schema(description = "생성 시각") LocalDateTime createdAt,
        @Schema(description = "마지막 수정 시각(MERGED/REJECTED/DEPLOYED로 전이된 시각 등)") LocalDateTime updatedAt
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
                result.approvalId(),
                result.prNumber(),
                result.mergeCommitSha(),
                result.mergedAt(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}
