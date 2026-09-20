package com.example.dvely.chat.presentation.dto;

import com.example.dvely.agent.domain.value.AiProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "대화 메시지 생성 요청")
public record SendMessageRequest(
        @Schema(description = "저장할 사용자 메시지 본문", example = "랜딩 페이지에 FAQ 섹션을 추가해줘")
        @NotBlank String content,

        @Schema(description = "이 메시지로 실행할 에이전트가 쓸 AI 제공자. 본인 키가 등록된 것만 쓸 수 있고 "
                + "생략할 수 없다 — 서버 키로 도는 기본 경로는 없다. "
                + "선택지는 GET /api/v1/agent/ai-providers 로 조회한다.",
                example = "CLAUDE_CODE")
        @NotNull AiProvider aiProvider
) {
}
