package com.example.dvely.preview.presentation.dto.request;

import com.example.dvely.preview.domain.value.PreviewRuntimeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "프리뷰 런타임 설정 저장 요청")
public record UpdatePreviewRuntimeRequest(
        @Schema(description = "런타임 타입", allowableValues = {"STATIC", "NODE_SERVER", "JAVA_FULLSTACK"},
                example = "NODE_SERVER")
        @NotNull PreviewRuntimeType runtimeType,

        @Schema(description = "서버형 실행 명령. 비우면 NODE_SERVER 는 npm start 로 실행", example = "npm start",
                nullable = true)
        String startCommand,

        @Schema(description = "JAVA_FULLSTACK 내부 라우팅 접두사. 비우면 /api", example = "/api", nullable = true)
        String apiPathPrefix,

        @Schema(description = "준비 확인 경로(선택)", nullable = true) String healthPath
) {}
