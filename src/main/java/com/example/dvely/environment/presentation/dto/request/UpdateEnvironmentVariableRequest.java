package com.example.dvely.environment.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * PATCH semantics: {@code value == null} means "keep the current value" (send {@code ""}
 * explicitly to set an empty value); {@code secret == null} means "keep the current flag".
 * {@code key}/{@code scope} have no fields here at all — they are immutable after creation.
 */
@Schema(description = "환경변수 수정 요청 (PATCH 시맨틱). key/scope는 필드 자체가 없음 — 이름을 바꾸려면 삭제 후 재생성.")
public record UpdateEnvironmentVariableRequest(
        @Schema(description = "새 값. 생략(null)하면 기존 값 유지, 빈 값으로 설정하려면 \"\"를 명시적으로 전달", nullable = true)
        @Size(max = 4096) String value,

        @Schema(description = "새 secret 여부. 생략(null)하면 기존 값 유지. true→false 전환은 400", nullable = true)
        Boolean secret
) {
}
