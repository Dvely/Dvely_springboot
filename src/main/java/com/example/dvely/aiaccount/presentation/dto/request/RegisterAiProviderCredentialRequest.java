package com.example.dvely.aiaccount.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Format rules beyond these annotations (no whitespace or control characters, which would break
 * the value when it is injected as a container environment variable) live in the domain
 * constructor, per the contract-first convention these annotations are only the first line of.
 */
@Schema(description = "본인 AI 제공자 API 키 등록/교체 요청. 같은 제공자로 다시 보내면 키가 교체됩니다.")
public record RegisterAiProviderCredentialRequest(

        @Schema(description = "본인 계정의 공식 API 키. 응답에는 마스킹된 형태만 돌아갑니다.",
                example = "sk-ant-api03-...")
        @NotBlank @Size(max = 512) String apiKey,

        @Schema(description = "키를 구분하기 위한 이름(선택)", example = "개인 계정", nullable = true)
        @Size(max = 64) String label
) {
}
