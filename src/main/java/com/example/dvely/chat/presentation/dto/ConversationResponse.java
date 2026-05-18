package com.example.dvely.chat.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "대화 세션 정보")
public record ConversationResponse(
        @Schema(description = "대화 ID", example = "101")
        Long conversationId,

        @Schema(description = "대화가 속한 프로젝트 ID", example = "12")
        Long projectId,

        @Schema(description = "휴지통 이동 여부", example = "false")
        boolean deleted,

        @Schema(description = "휴지통 이동 시각. 삭제되지 않은 대화는 null")
        LocalDateTime deletedAt,

        @Schema(description = "대화 생성 시각")
        LocalDateTime createdAt,

        @Schema(description = "대화 마지막 수정 시각")
        LocalDateTime updatedAt
) {
}
