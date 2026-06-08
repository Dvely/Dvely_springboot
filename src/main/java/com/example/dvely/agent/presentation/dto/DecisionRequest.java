package com.example.dvely.agent.presentation.dto;

import com.example.dvely.agent.domain.value.AiProvider;
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
        Long conversationId
) {}
