package com.example.dvely.apitoken.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "등록된 개인 액세스 토큰. 평문은 어떤 응답에도 포함되지 않습니다.")
public record ApiTokenResponse(

        @Schema(description = "토큰 ID", example = "1")
        Long apiTokenId,

        @Schema(description = "토큰 앞부분. 목록에서 어느 토큰인지 알아보기 위한 것이며 평문 재노출이 아닙니다.",
                example = "qp_a1b2c3d4")
        String tokenPrefix,

        @Schema(description = "권한", allowableValues = {"READ", "WRITE"}, example = "READ")
        String scope,

        @Schema(description = "사용자가 붙인 이름", nullable = true)
        String label,

        @Schema(description = "만료 시각")
        LocalDateTime expiresAt,

        @Schema(description = "마지막 사용 시각. 1시간 단위로 갱신됩니다", nullable = true)
        LocalDateTime lastUsedAt,

        @Schema(description = "발급 시각")
        LocalDateTime createdAt
) {
}
