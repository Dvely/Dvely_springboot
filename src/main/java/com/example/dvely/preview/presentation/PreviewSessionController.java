package com.example.dvely.preview.presentation;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.service.PreviewSessionService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/preview-sessions")
@RequiredArgsConstructor
public class PreviewSessionController {

    private final PreviewSessionService previewSessionService;

    @Operation(summary = "Preview session 종료")
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> close(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable String sessionId
    ) {
        if (!previewSessionService.closeOwned(sessionId, ownerUserId)) {
            throw new NotFoundException("PreviewSession을 찾을 수 없습니다. sessionId=" + sessionId);
        }
        return ResponseEntity.noContent().build();
    }
}
