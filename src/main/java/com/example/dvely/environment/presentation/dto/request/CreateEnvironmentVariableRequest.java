package com.example.dvely.environment.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code value} is {@code @NotNull} but NOT {@code @NotBlank} — an empty string is a valid
 * environment variable value (see EnvironmentVariable's value rules). Full format validation
 * (key pattern, 4096-char/NUL checks) happens in the domain constructor; these annotations are
 * only the first-line defense per the domain's contract-first convention.
 */
@Schema(description = "환경변수 생성 요청. 동일 (프로젝트, scope, key) 조합이 이미 있으면 409를 반환합니다.")
public record CreateEnvironmentVariableRequest(
        @Schema(description = "환경변수 키 (대소문자 구분, 최대 128자)", example = "STRIPE_SECRET_KEY")
        @NotBlank @Size(max = 128) String key,

        @Schema(description = "환경변수 값 (최대 4096자). 빈 문자열도 유효한 값", example = "sk_live_...")
        @NotNull @Size(max = 4096) String value,

        @Schema(description = "적용 스코프. COMMON 없음 — 두 환경 모두 적용하려면 각각 생성해야 함", allowableValues = {"PREVIEW", "PRODUCTION"}, example = "PRODUCTION")
        @NotBlank String scope,

        @Schema(description = "민감 정보 여부. true로 설정한 뒤에는 false로 되돌릴 수 없음")
        boolean secret
) {
}
