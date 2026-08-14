package com.example.dvely.preview.presentation;

import com.example.dvely.preview.application.service.ProjectPreviewService;
import com.example.dvely.preview.application.service.ProjectPreviewService.ProvisionOutcome;
import com.example.dvely.preview.presentation.dto.response.ProjectPreviewSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로젝트 단위 프리뷰 — 작업 지시 없이 "현재 상태"를 보기 위한 두 개의 엔드포인트.
 *
 * <p>FE 흐름은 이렇게 맞춰져 있다: 프로젝트 화면에 들어오면 GET 을 한 번 호출해 이미 떠 있는
 * 프리뷰가 있으면 곧바로 붙고(추가 비용 없음), 없으면 "프리뷰 띄우기" 버튼을 보여준다. 버튼을 누르면
 * POST 로 컨테이너를 띄우고 status 가 ACTIVE 가 될 때까지 폴링한다.</p>
 */
@Tag(name = "Preview", description = "Agent CODE 작업이 띄운 Docker 프리뷰 컨테이너에 대한 리버스 프록시 및 운영(상태/로그) 조회 API.")
@RestController
@RequiredArgsConstructor
public class ProjectPreviewController {

    private final ProjectPreviewService projectPreviewService;

    @Operation(
            summary = "프로젝트의 현재 프리뷰 조회",
            description = "프로젝트에 진입할 때 호출합니다. 지금 보여줄 프리뷰가 있으면 200과 함께 세션을, "
                    + "없으면 204(본문 없음)를 반환합니다 — 204는 오류가 아니라 \"아직 띄우지 않았다\"는 뜻이며, "
                    + "이때 FE는 프리뷰 띄우기 버튼(POST)을 보여주면 됩니다.\n\n"
                    + "반환되는 세션은 Agent 작업이 만든 것일 수도(taskId 있음), 이 API로 띄운 프로젝트 단위 "
                    + "프리뷰일 수도(taskId=null) 있습니다 — 둘 중 가장 최근에 사용된 하나입니다. "
                    + "status=ACTIVE 인 세션은 컨테이너가 실제로 살아 있는지 확인한 뒤에만 반환하며, "
                    + "컨테이너가 사라진 세션은 여기서 정리되고 204로 응답합니다(죽은 URL을 iframe에 걸지 않도록). "
                    + "Docker inspect 한 번이 포함되어 p95는 수십 ms 수준입니다."
    )
    @GetMapping("/api/v1/projects/{projectId}/preview-session")
    public ResponseEntity<ProjectPreviewSessionResponse> getCurrent(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        return projectPreviewService.findCurrent(projectId, ownerUserId)
                .map(result -> ResponseEntity.ok(ProjectPreviewSessionResponse.from(result)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(
            summary = "프로젝트 프리뷰 띄우기 (버튼)",
            description = "preview 브랜치의 현재 내용을 컨테이너에 clone → 빌드 → 서빙합니다.\n\n"
                    + "- **200**: 이미 서빙 중인 세션이 있어 그대로 붙었습니다. `previewUrl`을 바로 열 수 있습니다.\n"
                    + "- **202**: 준비를 시작했습니다(또는 이미 준비 중입니다). `status=PROVISIONING`이며 "
                    + "`previewUrl`은 아직 null입니다 — 같은 GET 또는 "
                    + "`GET /api/v1/preview-sessions/{sessionId}/status`로 폴링하다가 `status=ACTIVE`가 되면 엽니다. "
                    + "npm install·build를 포함하므로 보통 수십 초에서 수 분이 걸립니다.\n"
                    + "- **409**: GitHub 저장소가 연결되지 않은 프로젝트입니다(가져올 코드가 없음).\n\n"
                    + "요청이 겹쳐도 프로젝트당 컨테이너는 하나만 남습니다(먼저 만들어진 세션이 유지되고 나머지는 즉시 정리)."
    )
    @PostMapping("/api/v1/projects/{projectId}/preview-session")
    public ResponseEntity<ProjectPreviewSessionResponse> provision(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        ProvisionOutcome outcome = projectPreviewService.provision(projectId, ownerUserId);
        ProjectPreviewSessionResponse body = ProjectPreviewSessionResponse.from(outcome.session());
        return outcome.started()
                ? ResponseEntity.status(HttpStatus.ACCEPTED).body(body)
                : ResponseEntity.ok(body);
    }
}
