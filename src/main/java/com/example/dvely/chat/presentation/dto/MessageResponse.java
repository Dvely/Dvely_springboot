package com.example.dvely.chat.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "대화 메시지 정보")
public record MessageResponse(
        @Schema(description = "메시지 ID", example = "3001")
        Long messageId,

        @Schema(description = "메시지가 속한 대화 ID", example = "101")
        Long conversationId,

        @Schema(description = "메시지 역할. 현재 메시지 생성 API는 USER만 저장합니다.", example = "user")
        String role,

        @Schema(description = "메시지 본문", example = "랜딩 페이지에 FAQ 섹션을 추가해줘")
        String content,

        @Schema(description = "메시지 토큰 수. 현재 사용자 메시지는 0으로 저장됩니다.", example = "0")
        long tokenCount,

        @Schema(description = "메시지 생성 시각")
        LocalDateTime createdAt,

        @Schema(description = "메시지 저장과 함께 큐잉된 Agent 작업의 taskId. 승인 정책에 따라 작업이 정상 접수된 경우에만 " +
                "값이 채워지며, Decision Agent 판단 실패 등으로 작업이 생성되지 않았거나 과거 메시지를 조회하는 경우(GET " +
                "messages)에는 null입니다. 값이 있으면 GET /api/v1/agent/tasks/{taskId}로 진행 상황을 폴링할 수 있습니다.",
                example = "a1b2c3d4e5f6", nullable = true)
        String taskId
) {
}
