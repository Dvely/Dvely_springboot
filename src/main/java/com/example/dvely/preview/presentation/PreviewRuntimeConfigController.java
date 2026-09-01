package com.example.dvely.preview.presentation;

import com.example.dvely.preview.application.service.PreviewRuntimeConfigService;
import com.example.dvely.preview.presentation.dto.request.UpdatePreviewRuntimeRequest;
import com.example.dvely.preview.presentation.dto.response.PreviewRuntimeConfigResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "preview", description = "프리뷰 런타임 설정")
@RestController
@RequiredArgsConstructor
public class PreviewRuntimeConfigController {

    private final PreviewRuntimeConfigService runtimeConfigService;

    @Operation(summary = "프리뷰 런타임 설정 조회",
            description = "저장된 설정이 없으면 source=DEFAULT(STATIC)로 내려갑니다. 실제 프리뷰를 띄울 때는 "
                    + "설정이 없을 경우 클론 내용으로 자동 감지합니다.")
    @GetMapping("/api/v1/projects/{projectId}/preview/runtime")
    public PreviewRuntimeConfigResponse get(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        return PreviewRuntimeConfigResponse.from(runtimeConfigService.get(ownerUserId, projectId));
    }

    @Operation(summary = "프리뷰 런타임 설정 저장 (전체 교체)",
            description = "NODE_SERVER 는 앱이 3000에서 UI+API를 모두 서빙합니다. startCommand 를 비우면 npm start.\n\n"
                    + "이 PUT 은 전체 교체입니다 — 보낸 필드로 설정을 통째로 덮어씁니다. 안 보낸(null) apiPathPrefix "
                    + "는 기본값 /api 로, healthPath 는 null 로 리셋됩니다. 일부만 바꾸려면 먼저 GET 으로 읽어 "
                    + "그 값을 그대로 실어 보내세요(read-modify-write). (채팅 RUNTIME_SETUP 경로는 별도로 부분 갱신을 씁니다.)")
    @PutMapping("/api/v1/projects/{projectId}/preview/runtime")
    public PreviewRuntimeConfigResponse update(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId,
            @Valid @RequestBody UpdatePreviewRuntimeRequest request
    ) {
        return PreviewRuntimeConfigResponse.from(runtimeConfigService.upsert(
                ownerUserId, projectId, request.runtimeType(),
                request.startCommand(), request.apiPathPrefix(), request.healthPath(), request.dbEngine()));
    }
}
