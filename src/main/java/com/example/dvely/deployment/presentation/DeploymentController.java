package com.example.dvely.deployment.presentation;

import com.example.dvely.deployment.application.command.dto.DeployCommand;
import com.example.dvely.deployment.application.facade.DeploymentFacade;
import com.example.dvely.deployment.application.result.DeploymentCandidateResult;
import com.example.dvely.deployment.application.result.DeploymentFailureAnalysisResult;
import com.example.dvely.deployment.application.result.DeploymentHistoryResult;
import com.example.dvely.deployment.application.result.DeploymentLogsResult;
import com.example.dvely.deployment.application.result.DeploymentStatusResult;
import com.example.dvely.deployment.application.result.DeployResult;
import com.example.dvely.deployment.application.result.VersionDetailResult;
import com.example.dvely.deployment.application.result.VersionResult;
import com.example.dvely.deployment.presentation.dto.request.DeployRequest;
import com.example.dvely.deployment.presentation.dto.response.DeploymentCandidateResponse;
import com.example.dvely.deployment.presentation.dto.response.DeploymentFailureAnalysisResponse;
import com.example.dvely.deployment.presentation.dto.response.DeploymentHistoryResponse;
import com.example.dvely.deployment.presentation.dto.response.DeploymentLogsResponse;
import com.example.dvely.deployment.presentation.dto.response.DeploymentStatusResponse;
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
            description = "영속 Deployment Job을 생성하고 즉시 PENDING 상태로 반환합니다. " +
                          "worker가 프로젝트를 GitHub Pages로 배포합니다. " +
                          "deployTargetType이 LATEST이면 default branch의 최신 커밋 기준으로 배포하고, " +
                          "VERSION이면 versionName에 지정한 git tag 기준으로 배포합니다."
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
            summary = "배포 상태 조회",
            description = "특정 배포의 현재 상태를 반환합니다. " +
                          "status가 IN_PROGRESS인 경우 GitHub Actions의 실시간 빌드 상태(buildStatus)도 함께 반환합니다. " +
                          "buildStatus: queued(대기 중) | in_progress(빌드 중) | completed(빌드 완료). " +
                          "status가 LIVE 또는 FAILED이면 buildStatus는 null입니다."
    )
    @GetMapping("/api/v1/deployments/{deploymentId}")
    public DeploymentStatusResponse getDeploymentStatus(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회할 배포 이력 ID") @PathVariable Long deploymentId
    ) {
        DeploymentStatusResult result = deploymentFacade.getDeploymentStatus(userId, deploymentId);
        return new DeploymentStatusResponse(
                result.historyId(),
                result.projectId(),
                result.deployTargetType(),
                result.versionLabel(),
                result.deployedUrl(),
                result.status(),
                result.buildStatus(),
                result.buildConclusion(),
                result.triggeredAt(),
                result.updatedAt()
        );
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
        return deploymentFacade.getDeploymentHistories(userId, projectId).stream()
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
            summary = "배포 로그 조회",
            description = "특정 배포의 GitHub Actions 빌드 로그를 반환합니다. " +
                          "jobs 필드에는 각 Job의 step별 실행 상태가 포함됩니다. " +
                          "logText에는 첫 번째 Job의 전체 로그 텍스트가 포함됩니다. " +
                          "workflowRunId가 null인 경우 run_id가 아직 기록되지 않은 상태입니다."
    )
    @GetMapping("/api/v1/deployments/{deploymentId}/logs")
    public DeploymentLogsResponse getDeploymentLogs(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회할 배포 이력 ID") @PathVariable Long deploymentId
    ) {
        return toLogsResponse(deploymentFacade.getDeploymentLogs(userId, deploymentId));
    }

    @Operation(
            summary = "배포 재시도",
            description = "실패(FAILED)한 배포를 새 배포 이력으로 재큐잉합니다(기존 이력을 되돌리지 않음 — 실패 기록은 감사 목적으로 보존). " +
                          "대상과 동일한 deployTargetType으로, VERSION이면 동일 버전으로 재시도합니다. " +
                          "요청 본문은 없습니다. 대상이 FAILED가 아니면 409를 반환합니다."
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/v1/deployments/{deploymentId}/retry")
    public DeployResponse retryDeployment(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "재시도할 배포 이력 ID") @PathVariable Long deploymentId
    ) {
        return toDeployResponse(deploymentFacade.retryDeployment(userId, deploymentId));
    }

    @Operation(
            summary = "배포 실패 원인 분석 실행",
            description = "실패(FAILED)한 배포의 GitHub Actions 로그를 수집해 원인을 분석합니다. " +
                          "이미 저장된 분석이 있으면 LLM을 다시 호출하지 않고 그대로 반환합니다(멱등). " +
                          "신규 분석은 로그 수집과 LLM 호출로 인해 응답까지 약 15~30초가 걸릴 수 있습니다. " +
                          "대상이 FAILED가 아니면 409를 반환합니다."
    )
    @PostMapping("/api/v1/deployments/{deploymentId}/failure-analysis")
    public DeploymentFailureAnalysisResponse analyzeFailure(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "분석할 배포 이력 ID") @PathVariable Long deploymentId
    ) {
        return toAnalysisResponse(deploymentFacade.analyzeFailure(userId, deploymentId));
    }

    @Operation(
            summary = "배포 실패 원인 분석 조회",
            description = "저장된 분석 결과만 반환합니다(부작용 없음, LLM/GitHub 호출 없음). " +
                          "아직 분석을 실행한 적이 없으면 404를 반환하며, 이 경우 실행 API(POST)를 호출해야 합니다."
    )
    @GetMapping("/api/v1/deployments/{deploymentId}/failure-analysis")
    public DeploymentFailureAnalysisResponse getFailureAnalysis(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회할 배포 이력 ID") @PathVariable Long deploymentId
    ) {
        return toAnalysisResponse(deploymentFacade.getFailureAnalysis(userId, deploymentId));
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
                result.updatedAt(),
                result.retriedFromHistoryId()
        );
    }

    private DeploymentFailureAnalysisResponse toAnalysisResponse(DeploymentFailureAnalysisResult result) {
        return new DeploymentFailureAnalysisResponse(
                result.deploymentId(),
                result.summary(),
                result.logExcerpt(),
                result.suggestedFix(),
                result.analysisSource(),
                result.analyzedAt()
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

    private DeploymentLogsResponse toLogsResponse(DeploymentLogsResult result) {
        return new DeploymentLogsResponse(
                result.historyId(),
                result.workflowRunId(),
                result.jobs().stream()
                        .map(j -> new DeploymentLogsResponse.JobResponse(
                                j.jobId(),
                                j.name(),
                                j.status(),
                                j.conclusion(),
                                j.steps().stream()
                                        .map(s -> new DeploymentLogsResponse.StepResponse(
                                                s.number(), s.name(), s.status(), s.conclusion()))
                                        .toList()
                        ))
                        .toList(),
                result.logText()
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
