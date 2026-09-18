package com.example.dvely.apitoken.presentation.dto.request;

import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "개인 액세스 토큰 발급 요청. 평문 토큰은 이 요청의 응답에서 단 한 번만 반환됩니다.")
public record IssueApiTokenRequest(

        @Schema(description = "권한. READ = 조회만, WRITE = 조회 + 변경", example = "READ")
        @NotNull ApiTokenScope scope,

        @Schema(description = "구분용 이름(선택)", example = "내 노트북 Claude Code", nullable = true)
        @Size(max = 64) String label,

        @Schema(description = "만료까지 일수. 생략 시 90일, 최대 365일", example = "90", nullable = true)
        @Min(1) @Max(365) Integer expiresInDays
) {
}
