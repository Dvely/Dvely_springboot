package com.example.dvely.agent.presentation.dto;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.domain.value.ThinkingLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "에이전트 요청 본문")
public record DecisionRequest(

        @Schema(description = "자연어 요청 내용", example = "React로 투두 앱을 만들고 GitHub Pages에 배포해줘")
        @NotBlank
        String content,

        @Schema(description = "사용할 AI 제공자", example = "ANTHROPIC")
        @NotNull
        AiProvider aiProvider,

        @Schema(description = "수정할 기존 프로젝트 ID. null이면 신규 프로젝트로 처리", example = "42", nullable = true)
        Long projectId,

        @Schema(description = "요청이 시작된 대화 ID", example = "7", nullable = true)
        Long conversationId,

        @Schema(description = """
                사용할 모델 ID. null이면 서버에 설정된 제공자 기본 모델을 사용합니다.
                서버가 허용한 모델이 아니면 400을 반환합니다.
                """, example = "claude-opus-4-5-20251101", nullable = true)
        String model,

        @Schema(description = """
                모델의 사고 깊이. null이면 OFF입니다.
                해당 모델이 thinking을 지원하지 않으면 무시하지 않고 400을 반환합니다.
                """, example = "OFF", nullable = true)
        ThinkingLevel thinking
) {}
