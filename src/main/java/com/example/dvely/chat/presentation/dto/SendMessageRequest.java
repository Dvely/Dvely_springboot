package com.example.dvely.chat.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "대화 메시지 생성 요청")
public record SendMessageRequest(
        @Schema(description = "저장할 사용자 메시지 본문", example = "랜딩 페이지에 FAQ 섹션을 추가해줘")
        @NotBlank String content
) {
}
