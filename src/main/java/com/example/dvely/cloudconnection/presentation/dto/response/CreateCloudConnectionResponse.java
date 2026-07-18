package com.example.dvely.cloudconnection.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "클라우드 연결 등록 응답. 등록 직후 형식 검증만 완료된 상태이며, 실 권한 확인은 jobId로 별도 진행됩니다.")
public record CreateCloudConnectionResponse(
        @Schema(description = "생성된 클라우드 연결 ID") Long cloudConnectionId,
        @Schema(description = "클라우드 provider", allowableValues = {"AWS", "GCP"}) String provider,

        @Schema(description = "등록 직후 상태. 형식 검증만 거치므로 보통 VALIDATED(CHECKING이 아님)", example = "VALIDATED")
        String status,

        @Schema(description = "실 권한 확인 Job ID. GET /cloud-connection-verification-jobs/{jobId}로 진행 상태를 폴링")
        String jobId
) {
}
