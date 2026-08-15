package com.example.dvely.preview.presentation;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.result.PreviewAccessGrant;
import com.example.dvely.preview.application.result.PreviewContainerLogsResult;
import com.example.dvely.preview.application.result.PreviewContainerStatusResult;
import com.example.dvely.preview.application.service.PreviewContainerOpsService;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.preview.infrastructure.config.PreviewGatewayUrlResolver;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.security.PreviewAccessCookies;
import com.example.dvely.preview.presentation.dto.response.PreviewAccessResponse;
import com.example.dvely.preview.presentation.dto.response.PreviewContainerLogsResponse;
import com.example.dvely.preview.presentation.dto.response.PreviewContainerStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Preview", description = "Agent CODE 작업이 띄운 Docker 프리뷰 컨테이너에 대한 리버스 프록시 및 운영(상태/로그) 조회 API.")
@RestController
@RequestMapping("/api/v1/preview-sessions")
@RequiredArgsConstructor
public class PreviewSessionController {

    private final PreviewSessionService previewSessionService;
    private final PreviewContainerOpsService previewContainerOpsService;
    private final PreviewProperties previewProperties;
    private final PreviewGatewayUrlResolver gatewayUrlResolver;

    @Operation(
            summary = "프리뷰 열람 권한 발급 (iframe 표시 전 호출)",
            description = "세션 소유자임을 확인하고, 게이트웨이가 요구하는 소유권 쿠키를 발급합니다. "
                    + "쿠키는 `/api/v1/previews/{sessionId}/` 경로로만 전송되는 `HttpOnly` 쿠키이므로 "
                    + "FE가 값을 다룰 필요는 없고, **요청에 credentials(쿠키)를 포함**하기만 하면 됩니다.\n\n"
                    + "이 호출은 accessToken을 회전시킵니다 — 응답의 `previewUrl`이 유일하게 유효한 주소이며, "
                    + "이전에 받은 주소(작업 응답의 `previewUrl` 포함)는 즉시 404가 됩니다. "
                    + "채팅 기록이나 브라우저 히스토리로 흘러나간 예전 주소를 닫기 위한 동작입니다.\n\n"
                    + "404: 없거나 다른 유저 소유 / 409: 종료·만료됐거나 아직 준비 중(PROVISIONING)인 세션"
    )
    @PostMapping("/{sessionId}/access")
    public ResponseEntity<PreviewAccessResponse> grantAccess(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable String sessionId
    ) {
        PreviewAccessGrant grant = previewSessionService.grantAccess(
                sessionId, ownerUserId, previewProperties.getTtl());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie(grant).toString())
                .body(PreviewAccessResponse.from(grant));
    }

    /**
     * 경로를 세션 하나로 좁힌 HttpOnly 쿠키. `Secure`는 게이트웨이 오리진이 https일 때만 붙인다 —
     * 로컬(http)에서 무조건 붙이면 브라우저가 쿠키를 아예 보내지 않아 개발 중에 프리뷰가 열리지 않는다.
     * `SameSite=Lax`는 FE와 게이트웨이가 같은 사이트라는 현재 배치를 전제로 한다(프리뷰를 전용
     * 오리진으로 분리하면 `None`으로 바꿔야 한다).
     */
    private ResponseCookie accessCookie(PreviewAccessGrant grant) {
        return ResponseCookie.from(PreviewAccessCookies.COOKIE_NAME, grant.cookieValue())
                .httpOnly(true)
                .secure(gatewayUrlResolver.baseUrl().startsWith("https://"))
                .sameSite("Lax")
                .path(grant.cookiePath())
                .maxAge(grant.cookieMaxAge())
                .build();
    }

    @Operation(
            summary = "Preview session 종료",
            description = "PreviewSession을 즉시 만료 처리하고 연결된 Docker 컨테이너를 종료합니다. " +
                          "이미 종료/만료된 세션이거나 다른 유저 소유 세션이면 404를 반환합니다."
    )
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

    @Operation(
            summary = "Preview 컨테이너 상태 조회",
            description = "세션의 Docker 컨테이너 실행 여부·리소스 사용량을 조회합니다. "
                    + "stats one-shot 샘플링 특성상 CPU 델타 계산에 ~1초가 소요되어 이 API의 p95 지연은 약 1.5초입니다 "
                    + "— FE 폴링 주기는 5초 이상을 권장합니다. stats 조회가 3초를 넘기면 resources만 null로 응답합니다"
                    + "(상태 필드는 정상 반환). 종료된 세션도 조회 가능하며 이 경우 containerRunning=false로 응답합니다."
    )
    @GetMapping("/{sessionId}/status")
    public PreviewContainerStatusResponse getStatus(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable String sessionId
    ) {
        return toStatusResponse(previewContainerOpsService.getStatus(ownerUserId, sessionId));
    }

    @Operation(
            summary = "Preview 컨테이너 로그 조회",
            description = "세션의 Docker 컨테이너 stdout/stderr를 각 줄 타임스탬프가 포함된 단일 텍스트로 반환합니다. "
                    + "로그는 영속화되지 않으며 컨테이너 제거 시 함께 소멸합니다(다운로드/스트리밍 미지원). "
                    + "tail은 기본 200이며 [1, 2000] 범위를 벗어나면 에러 없이 경계값으로 클램프됩니다. "
                    + "sinceSeconds는 절대 시각이 아닌 '최근 N초' 상대값입니다. "
                    + "컨테이너가 이미 제거된 세션은 404가 아닌 containerRunning=false, logText=\"\" 200 응답입니다."
    )
    @GetMapping("/{sessionId}/logs")
    public PreviewContainerLogsResponse getLogs(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer tail,
            @RequestParam(required = false) Integer sinceSeconds
    ) {
        return toLogsResponse(previewContainerOpsService.getLogs(ownerUserId, sessionId, tail, sinceSeconds));
    }

    private PreviewContainerStatusResponse toStatusResponse(PreviewContainerStatusResult result) {
        return new PreviewContainerStatusResponse(
                result.sessionId(),
                result.projectId(),
                result.taskId(),
                result.sessionStatus(),
                result.containerRunning(),
                result.oomKilled(),
                result.exitCode(),
                result.startedAt(),
                result.expiresAt(),
                toResourceUsageResponse(result.resources())
        );
    }

    private PreviewContainerStatusResponse.ResourceUsageResponse toResourceUsageResponse(
            PreviewContainerStatusResult.ResourceUsageResult resources
    ) {
        if (resources == null) {
            return null;
        }
        return new PreviewContainerStatusResponse.ResourceUsageResponse(
                resources.memoryUsageBytes(),
                resources.memoryLimitBytes(),
                resources.memoryUsagePercent(),
                resources.cpuPercent()
        );
    }

    private PreviewContainerLogsResponse toLogsResponse(PreviewContainerLogsResult result) {
        return new PreviewContainerLogsResponse(
                result.sessionId(),
                result.containerRunning(),
                result.logText()
        );
    }
}
