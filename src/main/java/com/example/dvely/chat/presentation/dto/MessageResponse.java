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
        LocalDateTime createdAt
) {
}
