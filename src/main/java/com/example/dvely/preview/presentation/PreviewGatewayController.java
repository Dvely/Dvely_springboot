package com.example.dvely.preview.presentation;

import com.example.dvely.common.response.RawApiResponse;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewGatewayService;
import com.example.dvely.preview.application.service.PreviewSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Preview", description = "Agent CODE 작업이 띄운 Docker 프리뷰 컨테이너에 대한 리버스 프록시 및 운영(상태/로그) 조회 API.")
@RestController
@RawApiResponse
@RequiredArgsConstructor
public class PreviewGatewayController {

    private final PreviewSessionService previewSessionService;
    private final PreviewGatewayService previewGatewayService;

    @Operation(
            summary = "Preview 컨테이너 리버스 프록시",
            description = "sessionId/accessToken(URL에 내장된 발급 즉시 랜덤 토큰 — JWT 아님, iframe에서 별도 " +
                          "Authorization 헤더 없이 접근하기 위함)이 유효하면 요청을 해당 Docker 컨테이너로 그대로 프록시합니다. " +
                          "응답 Content-Type은 프리뷰 앱이 반환하는 값 그대로이며(HTML/JS/CSS/이미지 등), 이 API 자체는 " +
                          "공통 응답 envelope로 감싸지 않습니다(@RawApiResponse). 세션이 없거나 토큰이 일치하지 않으면 404입니다. " +
                          "Swagger UI \"Try it out\"으로 직접 호출하기보다는 taskId 폴링으로 받은 previewUrl을 브라우저에서 여는 용도입니다."
    )
    @GetMapping({
            "/api/v1/previews/{sessionId}/{accessToken}",
            "/api/v1/previews/{sessionId}/{accessToken}/**"
    })
    public ResponseEntity<byte[]> proxy(
            @Parameter(description = "Preview 세션 ID") @PathVariable String sessionId,
            @Parameter(description = "세션 발급 시 함께 생성된 1회성 접근 토큰(랜덤 UUID)") @PathVariable String accessToken,
            HttpServletRequest request
    ) {
        PreviewSessionInfo session = previewSessionService.resolveGateway(sessionId, accessToken)
                .orElse(null);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        String prefix = "/api/v1/previews/" + sessionId + "/" + accessToken + "/";
        String requestUri = request.getRequestURI();
        int prefixIndex = requestUri.indexOf(prefix);
        String path = prefixIndex < 0 ? "" : requestUri.substring(prefixIndex + prefix.length());
        return previewGatewayService.proxy(
                session,
                prefix,
                path,
                request.getQueryString()
        );
    }
}
