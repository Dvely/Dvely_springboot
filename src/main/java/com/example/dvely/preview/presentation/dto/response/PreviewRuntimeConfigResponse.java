package com.example.dvely.preview.presentation.dto.response;

import com.example.dvely.preview.application.result.PreviewRuntimeConfigResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프리뷰 런타임 설정. source 로 저장값/기본값/자동감지를 구분한다.")
public record PreviewRuntimeConfigResponse(
        @Schema(description = "프로젝트 ID") Long projectId,
        @Schema(description = "런타임 타입", allowableValues = {"STATIC", "NODE_SERVER", "JAVA_FULLSTACK"})
        String runtimeType,
        @Schema(description = "서버형 실행 명령. null 이면 NODE_SERVER 는 npm start", nullable = true)
        String startCommand,
        @Schema(description = "JAVA_FULLSTACK 라우팅 접두사") String apiPathPrefix,
        @Schema(description = "준비 확인 경로", nullable = true) String healthPath,
        @Schema(description = "값 출처", allowableValues = {"STORED", "DEFAULT", "DETECTED"}) String source
) {
    public static PreviewRuntimeConfigResponse from(PreviewRuntimeConfigResult r) {
        return new PreviewRuntimeConfigResponse(r.projectId(), r.runtimeType(), r.startCommand(),
                r.apiPathPrefix(), r.healthPath(), r.source());
    }
}
