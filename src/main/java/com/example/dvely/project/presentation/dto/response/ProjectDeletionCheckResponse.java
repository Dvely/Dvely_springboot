package com.example.dvely.project.presentation.dto.response;

import com.example.dvely.cloudresource.presentation.dto.LiveResourceResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "지금 프로젝트를 삭제할 수 있는지. 삭제 실패(409 PROJECT_DELETION_BLOCKED)의 data 도 같은 모양이다")
public record ProjectDeletionCheckResponse(
        @Schema(description = "liveResources 가 비어 있으면 true")
        boolean deletable,
        @Schema(description = "먼저 정리해야 하는 자원(SERVER·DATABASE·DEPLOYMENT). 서버·DB 는 인프라 탭에서 종료하고, 배포는 끝나기를 "
                + "기다린 뒤 다시 요청하면 된다")
        List<LiveResourceResponse> liveResources
) {

    public static ProjectDeletionCheckResponse of(List<LiveResourceResponse> liveResources) {
        return new ProjectDeletionCheckResponse(liveResources.isEmpty(), liveResources);
    }
}
