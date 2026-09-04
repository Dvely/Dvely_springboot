package com.example.dvely.chat.presentation.dto;

import com.example.dvely.agent.domain.value.AiProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "대화 메시지 생성 요청")
public record SendMessageRequest(
        @Schema(description = "저장할 사용자 메시지 본문", example = "랜딩 페이지에 FAQ 섹션을 추가해줘")
        @NotBlank String content,

        @Schema(description = "이 메시지로 실행할 에이전트가 쓸 AI 제공자. null 이면 서버 기본값. "
                + "선택지는 GET /api/v1/agent/ai-providers 로 조회한다.",
                example = "GLM", nullable = true)
        AiProvider aiProvider
) {
}
