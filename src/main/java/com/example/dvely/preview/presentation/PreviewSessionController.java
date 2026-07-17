package com.example.dvely.preview.presentation;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.result.PreviewContainerLogsResult;
import com.example.dvely.preview.application.result.PreviewContainerStatusResult;
import com.example.dvely.preview.application.service.PreviewContainerOpsService;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.preview.presentation.dto.response.PreviewContainerLogsResponse;
import com.example.dvely.preview.presentation.dto.response.PreviewContainerStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/preview-sessions")
@RequiredArgsConstructor
public class PreviewSessionController {

    private final PreviewSessionService previewSessionService;
    private final PreviewContainerOpsService previewContainerOpsService;

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
