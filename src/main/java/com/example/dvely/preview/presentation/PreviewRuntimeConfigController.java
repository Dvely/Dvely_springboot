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

    @Operation(summary = "프리뷰 런타임 설정 저장",
            description = "NODE_SERVER 는 앱이 3000에서 UI+API를 모두 서빙합니다. startCommand 를 비우면 npm start. "
                    + "JAVA_FULLSTACK 실행은 다음 단계입니다(저장은 됩니다).")
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
