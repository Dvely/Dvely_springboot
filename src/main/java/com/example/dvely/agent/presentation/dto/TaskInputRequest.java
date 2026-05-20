package com.example.dvely.agent.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "에이전트 입력 대기 응답 본문")
public record TaskInputRequest(

        @Schema(description = "에이전트 질문에 대한 사용자 응답값. 예: GitHub 저장소 이름, 도메인 주소 등",
                example = "my-react-app")
        @NotBlank
        String value
) {}
