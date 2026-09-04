package com.example.dvely.aiaccount.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "등록된 AI 제공자 크리덴셜. 평문 키는 어떤 응답에도 포함되지 않습니다.")
public record AiProviderCredentialResponse(

        @Schema(description = "크리덴셜 ID", example = "1")
        Long aiProviderCredentialId,

        @Schema(description = "벤더", allowableValues = {"ANTHROPIC", "OPENAI", "GLM"}, example = "ANTHROPIC")
        String provider,

        @Schema(description = "마스킹된 키. 앞 6자만 남기고 가립니다 — 어느 키를 넣었는지 알아보되 "
                + "꼬리(실제 엔트로피)는 노출하지 않기 위함입니다.", example = "sk-ant****")
        String maskedApiKey,

        @Schema(description = "사용자가 붙인 이름", example = "개인 계정", nullable = true)
        String label,

        @Schema(description = "등록 시각")
        LocalDateTime createdAt,

        @Schema(description = "마지막 교체 시각")
        LocalDateTime updatedAt
) {
}
