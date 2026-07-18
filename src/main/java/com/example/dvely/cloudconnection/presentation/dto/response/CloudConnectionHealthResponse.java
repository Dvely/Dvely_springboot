package com.example.dvely.cloudconnection.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "클라우드 연결 health 상태. 마지막으로 저장된 검증 결과를 반환합니다(호출 시점에 재검증하지 않음 — " +
        "재검증은 POST verification-jobs로 별도 요청).")
public record CloudConnectionHealthResponse(
        @Schema(description = "클라우드 연결 ID") Long cloudConnectionId,
        @Schema(description = "클라우드 provider", allowableValues = {"AWS", "GCP"}) String provider,

        @Schema(
                description = "연결 상태. CONNECTED면 정상, 그 외는 REGION_UNSUPPORTED/INVALID_CREDENTIAL 등 사유별 상태",
                example = "CONNECTED"
        )
        String status,

        @Schema(description = "상태에 대한 사람이 읽을 수 있는 설명") String message,
        @Schema(description = "이 상태가 마지막으로 확인된 시각") LocalDateTime checkedAt
) {
}
