package com.example.dvely.withdrawal.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "지금 탈퇴할 수 있는지. 탈퇴 실패(409)의 data 도 같은 모양이다")
public record WithdrawalCheckResponse(
        @Schema(description = "liveResources 가 비어 있으면 true")
        boolean withdrawable,
        @Schema(description = "먼저 정리해야 하는 자원. 정리한 뒤 다시 요청하면 된다")
        List<LiveResourceResponse> liveResources
) {

    public static WithdrawalCheckResponse of(List<LiveResourceResponse> liveResources) {
        return new WithdrawalCheckResponse(liveResources.isEmpty(), liveResources);
    }
}
