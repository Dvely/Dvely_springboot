package com.example.dvely.preview.presentation;

import com.example.dvely.common.response.RawApiResponse;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewGatewayService;
import com.example.dvely.preview.application.service.PreviewSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RawApiResponse
@RequiredArgsConstructor
public class PreviewGatewayController {

    private final PreviewSessionService previewSessionService;
    private final PreviewGatewayService previewGatewayService;

    @GetMapping({
            "/api/v1/previews/{sessionId}/{accessToken}",
            "/api/v1/previews/{sessionId}/{accessToken}/**"
    })
    public ResponseEntity<byte[]> proxy(
            @PathVariable String sessionId,
            @PathVariable String accessToken,
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
