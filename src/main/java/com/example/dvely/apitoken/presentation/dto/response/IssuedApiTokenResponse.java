package com.example.dvely.apitoken.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        발급 결과. token 은 이 응답에서만 볼 수 있고 이후 어떤 API 로도 다시 조회할 수 없습니다 —
        서버는 해시만 보관합니다. 잃어버리면 새로 발급해야 합니다.
        """)
public record IssuedApiTokenResponse(

        @Schema(description = "평문 토큰. **이 응답에서만 노출됩니다.**", example = "qp_a1b2c3d4...")
        String token,

        @Schema(description = "토큰 정보")
        ApiTokenResponse info
) {
}
