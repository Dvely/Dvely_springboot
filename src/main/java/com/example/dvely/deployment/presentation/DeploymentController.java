package com.example.dvely.deployment.presentation;

import com.example.dvely.deployment.application.command.dto.DeployCommand;
import com.example.dvely.deployment.application.facade.DeploymentFacade;
import com.example.dvely.deployment.application.result.DeploymentCandidateResult;
import com.example.dvely.deployment.application.result.DeploymentHistoryResult;
import com.example.dvely.deployment.application.result.DeployResult;
import com.example.dvely.deployment.application.result.VersionDetailResult;
import com.example.dvely.deployment.application.result.VersionResult;
import com.example.dvely.deployment.presentation.dto.request.DeployRequest;
import com.example.dvely.deployment.presentation.dto.response.DeploymentCandidateResponse;
import com.example.dvely.deployment.presentation.dto.response.DeploymentHistoryResponse;
import com.example.dvely.deployment.presentation.dto.response.DeployResponse;
import com.example.dvely.deployment.presentation.dto.response.VersionDetailResponse;
import com.example.dvely.deployment.presentation.dto.response.VersionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Deployment", description = "배포 버전 관리 API. 프로젝트의 merge 기반 버전 목록 조회 및 배포 이력을 제공합니다.")
@RestController
@RequiredArgsConstructor
public class DeploymentController {

    private final DeploymentFacade deploymentFacade;

    @Operation(
            summary = "GitHub Pages 배포 요청",
            description = "프로젝트를 GitHub Pages로 배포합니다. " +
                          "deployTargetType이 LATEST이면 default branch의 최신 커밋 기준으로 배포하고, " +
                          "VERSION이면 versionName에 지정한 git tag 기준으로 배포합니다. " +
                          "요청 즉시 PENDING 상태로 응답하며, 실제 배포는 GitHub Pages API를 통해 비동기로 진행됩니다."
    )
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/api/v1/projects/{projectId}/deployments")
    public DeployResponse deploy(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "배포할 프로젝트 ID") @PathVariable Long projectId,
            @Valid @RequestBody DeployRequest request
    ) {
        DeployCommand command = new DeployCommand(request.deployTargetType(), request.versionName());
        return toDeployResponse(deploymentFacade.deploy(userId, projectId, command));
    }

    @Operation(
            summary = "배포 이력 목록 조회",
            description = "프로젝트에서 실행된 모든 배포 이력을 최신순으로 반환합니다. " +
                          "성공/실패 여부와 관계없이 배포를 요청한 모든 이력을 포함합니다."
    )
    @GetMapping("/api/v1/projects/{projectId}/deployments")
    public List<DeploymentHistoryResponse> getDeploymentHistories(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return deploymentFacade.getDeploymentHistories(projectId).stream()
                .map(this::toHistoryResponse)
                .toList();
    }

    @Operation(
            summary = "프로젝트 버전 목록 조회",
            description = "프로젝트에 연결된 저장소의 merge 커밋을 기준으로 생성된 버전 목록을 반환합니다. " +
                          "배포 히스토리 화면에서 버전별 배포 상태와 커밋 정보를 보여줄 때 사용합니다."
    )
    @GetMapping("/api/v1/projects/{projectId}/versions")
    public List<VersionResponse> getVersions(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "버전 목록을 조회할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return deploymentFacade.getVersions(userId, projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Operation(
            summary = "배포 가능 후보 목록 조회",
            description = "프로젝트의 배포 이력 중 성공(LIVE, PREVIEW_READY) 상태인 버전만 추려 반환합니다. " +
                          "재배포 또는 롤백 대상 선택 화면에서 배포 가능한 후보 목록을 보여줄 때 사용합니다."
    )
    @GetMapping("/api/v1/projects/{projectId}/deployment-candidates")
    public List<DeploymentCandidateResponse> getDeploymentCandidates(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "후보 목록을 조회할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return deploymentFacade.getDeploymentCandidates(userId, projectId).stream()
                .map(this::toCandidateResponse)
                .toList();
    }

    @Operation(
            summary = "버전 상세 조회",
            description = "특정 버전의 상세 정보를 반환합니다. " +
                          "버전 목록에서 항목을 선택했을 때 merge 유저, PR 번호, 배포 URL 등 " +
                          "상세 내용을 보여주는 화면에서 사용합니다."
    )
    @GetMapping("/api/v1/versions/{versionId}")
    public VersionDetailResponse getVersionDetail(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회할 버전 ID") @PathVariable Long versionId
    ) {
        return toDetailResponse(deploymentFacade.getVersionDetail(userId, versionId));
    }

    private DeploymentHistoryResponse toHistoryResponse(DeploymentHistoryResult result) {
        return new DeploymentHistoryResponse(
                result.historyId(),
                result.projectId(),
                result.deployTargetType(),
                result.versionLabel(),
                result.deployedUrl(),
                result.status(),
                result.triggeredAt(),
                result.updatedAt()
        );
    }

    private DeployResponse toDeployResponse(DeployResult result) {
        return new DeployResponse(
                result.deploymentId(),
                result.projectId(),
                result.deployTargetType(),
                result.versionName(),
                result.status(),
                result.pagesUrl(),
                result.createdAt()
        );
    }

    private VersionResponse toResponse(VersionResult result) {
        return new VersionResponse(
                result.versionId(),
                result.versionName(),
                result.commitSha(),
                result.title(),
                result.deployStatus(),
                result.mergedAt()
        );
    }

    private DeploymentCandidateResponse toCandidateResponse(DeploymentCandidateResult result) {
        return new DeploymentCandidateResponse(
                result.versionId(),
                result.versionName(),
                result.commitSha(),
                result.title(),
                result.deployStatus(),
                result.deployedUrl(),
                result.deployedAt()
        );
    }

    private VersionDetailResponse toDetailResponse(VersionDetailResult result) {
        return new VersionDetailResponse(
                result.versionId(),
                result.versionName(),
                result.commitSha(),
                result.title(),
                result.description(),
                result.deployStatus(),
                result.deployedUrl(),
                result.mergedBy(),
                result.mergedByAvatarUrl(),
                result.prNumber(),
                result.mergedAt()
        );
    }
}
