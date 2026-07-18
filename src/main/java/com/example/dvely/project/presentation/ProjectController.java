package com.example.dvely.project.presentation;

import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.command.dto.ConnectProjectRepositoryCommand;
import com.example.dvely.project.application.command.dto.UpdateProjectCommand;
import com.example.dvely.project.application.command.dto.ProjectDeleteMode;
import com.example.dvely.project.application.facade.ProjectFacade;
import com.example.dvely.project.application.result.ProjectChatSettingsResult;
import com.example.dvely.project.application.service.ProjectChatSettingsService;
import com.example.dvely.project.application.result.ProjectCostBudgetResult;
import com.example.dvely.project.application.result.ProjectInfrastructureChangeResult;
import com.example.dvely.project.application.result.ProjectInfrastructureConfigurationResult;
import com.example.dvely.project.application.result.ProjectInfrastructureSettingsResult;
import com.example.dvely.project.application.service.ProjectCostBudgetService;
import com.example.dvely.project.application.service.ProjectInfrastructureConfigurationService;
import com.example.dvely.project.application.service.ProjectInfrastructureSettingsService;
import com.example.dvely.project.infrastructure.mapper.ProjectMapper;
import com.example.dvely.project.presentation.dto.request.ConnectProjectRepositoryRequest;
import com.example.dvely.project.presentation.dto.request.CreateProjectRequest;
import com.example.dvely.project.presentation.dto.request.UpdateProjectRequest;
import com.example.dvely.project.presentation.dto.request.UpdateProjectBudgetRequest;
import com.example.dvely.project.presentation.dto.request.UpdateProjectChatSettingsRequest;
import com.example.dvely.project.presentation.dto.request.UpdateProjectInfrastructureConfigurationRequest;
import com.example.dvely.project.presentation.dto.request.UpdateProjectInfrastructureSettingsRequest;
import com.example.dvely.project.presentation.dto.response.GithubRepositoryResponse;
import com.example.dvely.project.presentation.dto.response.ProjectActivityLogResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCommitResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCostBudgetResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCreateResponse;
import com.example.dvely.project.presentation.dto.response.ProjectDetailResponse;
import com.example.dvely.project.presentation.dto.response.ProjectInfrastructureChangeResponse;
import com.example.dvely.project.presentation.dto.response.ProjectInfrastructureConfigurationResponse;
import com.example.dvely.project.presentation.dto.response.ProjectOverviewResponse;
import com.example.dvely.project.presentation.dto.response.ProjectRepositoryResponse;
import com.example.dvely.project.presentation.dto.response.ProjectRepositorySettingsResponse;
import com.example.dvely.project.presentation.dto.response.ProjectChatSettingsResponse;
import com.example.dvely.project.presentation.dto.response.ProjectInfrastructureSettingsResponse;
import com.example.dvely.project.presentation.dto.response.RepositoryHealthResponse;
import com.example.dvely.project.presentation.dto.response.ProjectSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Project", description = "프로젝트 생성, 조회, 수정, 삭제와 GitHub 저장소 연결/상태 확인 API")
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectFacade projectFacade;
    private final ProjectMapper projectMapper;
    private final ProjectChatSettingsService projectChatSettingsService;
    private final ProjectInfrastructureSettingsService projectInfrastructureSettingsService;
    private final ProjectInfrastructureConfigurationService projectInfrastructureConfigurationService;
    private final ProjectCostBudgetService projectCostBudgetService;

    @Operation(
            summary = "프로젝트 생성",
            description = "프로젝트를 DRAFT 상태로 저장하고 startMode/templateType/draftMode에 맞는 " +
                          "초기 코드 생성 Agent task를 제출합니다. GitHub 저장소 연결은 생성 후 별도로 처리합니다."
    )
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProjectCreateResponse createProject(@Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
                                               @Valid @RequestBody CreateProjectRequest request) {
        var result = projectFacade.createProject(ownerUserId, new CreateProjectCommand(
                request.name(),
                request.startMode(),
                request.templateType(),
                request.draftMode()
        ));
        return projectMapper.toCreateResponse(result);
    }

    @Operation(
            summary = "프로젝트 GitHub 저장소 연결",
            description = "프로젝트에 GitHub 저장소를 연결합니다. " +
                          "repositoryMode가 create이면 새 저장소를 만들고, existing이면 repositoryFullName으로 기존 저장소 접근 가능 여부를 확인합니다. " +
                          "연결 후 preview 브랜치를 준비하고 저장소 binding/health 상태를 갱신합니다."
    )
    @PostMapping("/{projectId}/repository")
    public ProjectRepositoryResponse connectRepository(@Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
                                                       @Parameter(description = "저장소를 연결할 프로젝트 ID") @PathVariable Long projectId,
                                                       @Valid @RequestBody ConnectProjectRepositoryRequest request) {
        var result = projectFacade.connectRepository(ownerUserId, projectId, new ConnectProjectRepositoryCommand(
                request.repositoryMode(),
                request.repositoryName(),
                request.repositoryFullName(),
                request.repositoryVisibility()
        ));
        return projectMapper.toProjectRepositoryResponse(result);
    }

    @Operation(
            summary = "프로젝트 GitHub 저장소 연결 해제",
            description = "프로젝트에서 GitHub 저장소 연결 정보를 제거합니다. GitHub 저장소·워크플로·Pages는 삭제되지 않으며, " +
                          "배포 이력·도메인 연결 등 다른 도메인의 상태도 별도로 정리하지 않습니다(자연 단절). " +
                          "해제 후 POST로 동일하거나 다른 저장소를 다시 연결할 수 있습니다."
    )
    @DeleteMapping("/{projectId}/repository")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnectRepository(@Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
                                     @Parameter(description = "저장소 연결을 해제할 프로젝트 ID") @PathVariable Long projectId) {
        projectFacade.disconnectRepository(ownerUserId, projectId);
    }

    @Operation(
            summary = "GitHub 저장소 목록 조회",
            description = "현재 유저가 GitHub App/OAuth 연동으로 접근할 수 있는 저장소 목록을 조회합니다. " +
                          "기존 저장소를 프로젝트에 연결하는 화면에서 사용합니다."
    )
    @GetMapping("/github/repositories")
    public List<GithubRepositoryResponse> getGithubRepositories(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId
    ) {
        return projectFacade.getGithubRepositories(ownerUserId).stream()
                .map(projectMapper::toGithubRepositoryResponse)
                .toList();
    }

    @Operation(
            summary = "프로젝트 목록 조회",
            description = "현재 유저가 소유한 삭제되지 않은 프로젝트 목록을 최신 수정순으로 조회합니다. " +
                          "프로젝트 홈의 카드 목록 렌더링에 사용합니다."
    )
    @GetMapping
    public List<ProjectSummaryResponse> getProjects(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId
    ) {
        return projectFacade.getProjects(ownerUserId).stream()
                .map(projectMapper::toSummaryResponse)
                .toList();
    }

    @Operation(
            summary = "프로젝트 상세 조회",
            description = "프로젝트의 이름, 상태, 시작 방식, 템플릿, 작성 모드 등 기본 메타데이터를 조회합니다."
    )
    @GetMapping("/{projectId}")
    public ProjectDetailResponse getProject(@Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
                                            @Parameter(description = "조회할 프로젝트 ID") @PathVariable Long projectId) {
        var result = projectFacade.getProject(ownerUserId, projectId);
        return projectMapper.toDetailResponse(result);
    }

    @Operation(
            summary = "프로젝트 수정",
            description = "프로젝트명을 수정합니다. 현재 공개 API에서는 프로젝트 이름만 수정할 수 있습니다."
    )
    @PatchMapping("/{projectId}")
    public ProjectDetailResponse updateProject(@Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
                                               @Parameter(description = "수정할 프로젝트 ID") @PathVariable Long projectId,
                                               @Valid @RequestBody UpdateProjectRequest request) {
        var result = projectFacade.updateProject(ownerUserId, projectId, new UpdateProjectCommand(request.name()));
        return projectMapper.toDetailResponse(result);
    }

    @Operation(
            summary = "프로젝트 삭제",
            description = "프로젝트를 삭제합니다. deleteMode가 없으면 PROJECT_ONLY로 처리되어 프로젝트를 soft delete하고 대화는 휴지통으로 이동합니다. " +
                          "PROJECT_AND_REPOSITORY를 지정하면 연결된 GitHub 저장소 삭제를 시도하고 관련 대화를 함께 제거합니다."
    )
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
                              @Parameter(description = "삭제할 프로젝트 ID") @PathVariable Long projectId,
                              @Parameter(description = "삭제 범위. PROJECT_ONLY(기본값) | PROJECT_AND_REPOSITORY")
                              @RequestParam(name = "deleteMode", required = false) String deleteMode) {
        projectFacade.deleteProject(ownerUserId, projectId, ProjectDeleteMode.from(deleteMode));
    }

    @Operation(
            summary = "프로젝트 개요 조회",
            description = "현재 도메인 URL, 배포 상태와 버전, 최근 운영 이벤트 3개, 최신 커밋, 저장소·클라우드 상태와 운영 조치를 조회합니다."
    )
    @GetMapping("/{projectId}/overview")
    public ProjectOverviewResponse getOverview(@Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
                                               @Parameter(description = "개요를 조회할 프로젝트 ID") @PathVariable Long projectId) {
        var result = projectFacade.getOverview(ownerUserId, projectId);
        return projectMapper.toOverviewResponse(result);
    }

    @Operation(
            summary = "프로젝트 활동 로그 조회",
            description = "Deployment, Change, Approval, Domain의 실제 저장 이력을 프로젝트 생성 이벤트와 함께 최신순으로 조회합니다."
    )
    @GetMapping("/{projectId}/activity-logs")
    public List<ProjectActivityLogResponse> getActivityLogs(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @Parameter(description = "활동 로그를 조회할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return projectFacade.getActivityLogs(ownerUserId, projectId).stream()
                .map(projectMapper::toActivityLogResponse)
                .toList();
    }

    @Operation(
            summary = "프로젝트 커밋 목록 조회",
            description = "프로젝트에 연결된 GitHub 저장소의 최근 커밋 목록을 조회합니다. " +
                          "저장소가 아직 연결되지 않은 프로젝트는 빈 목록을 반환합니다."
    )
    @GetMapping("/{projectId}/commits")
    public List<ProjectCommitResponse> getCommits(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @Parameter(description = "커밋 목록을 조회할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return projectFacade.getCommits(ownerUserId, projectId).stream()
                .map(projectMapper::toCommitResponse)
                .toList();
    }

    @Operation(
            summary = "프로젝트 저장소 health 확인",
            description = "프로젝트에 연결된 GitHub 저장소가 존재하고 현재 유저가 접근 가능한지 확인합니다. " +
                          "연결 저장소가 없으면 REPOSITORY_NOT_FOUND를 반환합니다."
    )
    @GetMapping("/{projectId}/repository-health")
    public RepositoryHealthResponse getRepositoryHealth(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @Parameter(description = "저장소 상태를 확인할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return projectMapper.toRepositoryHealthResponse(projectFacade.getRepositoryHealth(ownerUserId, projectId));
    }

    @Operation(summary = "프로젝트 Chat 승인 정책 조회")
    @GetMapping("/{projectId}/settings/chat")
    public ProjectChatSettingsResponse getChatSettings(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        return toChatSettingsResponse(projectChatSettingsService.get(ownerUserId, projectId));
    }

    @Operation(summary = "프로젝트 Chat 승인 정책 수정")
    @PatchMapping("/{projectId}/settings/chat")
    public ProjectChatSettingsResponse updateChatSettings(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId,
            @Valid @RequestBody UpdateProjectChatSettingsRequest request
    ) {
        return toChatSettingsResponse(projectChatSettingsService.update(
                ownerUserId,
                projectId,
                request.changeApprovalRequired(),
                request.deploymentApprovalRequired(),
                request.domainApprovalRequired(),
                request.infraApprovalRequired()
        ));
    }

    @Operation(summary = "프로젝트 Infrastructure 설정 조회")
    @GetMapping("/{projectId}/settings/infrastructure")
    public ProjectInfrastructureSettingsResponse getInfrastructureSettings(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        return toInfrastructureSettingsResponse(projectInfrastructureSettingsService.get(ownerUserId, projectId));
    }

    @Operation(
            summary = "프로젝트 클라우드 연결 선택",
            description = "실제 권한 확인이 완료된 CONNECTED 연결만 선택할 수 있습니다."
    )
    @PutMapping("/{projectId}/settings/infrastructure")
    public ProjectInfrastructureSettingsResponse updateInfrastructureSettings(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId,
            @Valid @RequestBody UpdateProjectInfrastructureSettingsRequest request
    ) {
        return toInfrastructureSettingsResponse(projectInfrastructureSettingsService.select(
                ownerUserId,
                projectId,
                request.cloudConnectionId()
        ));
    }

    @Operation(summary = "프로젝트 클라우드 연결 선택 해제")
    @DeleteMapping("/{projectId}/settings/infrastructure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearInfrastructureSettings(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        projectInfrastructureSettingsService.clear(ownerUserId, projectId);
    }

    @Operation(
            summary = "프로젝트 인프라 설정 조회",
            description = "배포 아키텍처/컴퓨팅 티어/스토리지/네트워크 4개 설정과 승인 대기 중인 변경을 조회합니다. " +
                          "CONNECTED 클라우드 연결이 선택되지 않은 프로젝트도 200으로 응답하며 configurable=false로 표시합니다."
    )
    @GetMapping("/{projectId}/settings/infrastructure/configuration")
    public ProjectInfrastructureConfigurationResponse getInfrastructureConfiguration(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @Parameter(description = "설정을 조회할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return toInfrastructureConfigurationResponse(
                projectInfrastructureConfigurationService.get(ownerUserId, projectId));
    }

    @Operation(
            summary = "프로젝트 인프라 설정 저장/변경",
            description = "CONNECTED 클라우드 연결이 선택되어 있어야 합니다(아니면 409). " +
                          "Chat 설정의 infraApprovalRequired가 true(기본값)면 즉시 적용되지 않고 INFRA_OPERATION 승인이 생성되며 " +
                          "settings는 기존 값을 유지한 채 pendingChange로 대기 건이 반환됩니다. false면 즉시 적용됩니다. " +
                          "현재 적용값과 완전히 동일한 요청은 이력·승인 생성 없이 현재 상태 그대로 반환됩니다(no-op). " +
                          "승인 대기 중인 변경이 이미 있으면 409를 반환합니다(먼저 처리 필요)."
    )
    @PutMapping("/{projectId}/settings/infrastructure/configuration")
    public ProjectInfrastructureConfigurationResponse updateInfrastructureConfiguration(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @Parameter(description = "설정을 저장할 프로젝트 ID") @PathVariable Long projectId,
            @Valid @RequestBody UpdateProjectInfrastructureConfigurationRequest request
    ) {
        return toInfrastructureConfigurationResponse(projectInfrastructureConfigurationService.update(
                ownerUserId,
                projectId,
                request.deploymentArchitecture(),
                request.computeTier(),
                request.storageType(),
                request.networkAccess()
        ));
    }

    @Operation(
            summary = "프로젝트 인프라 설정 변경 이력 조회",
            description = "limit 기본 50, 최대 200(초과 시 200으로 보정, 0·음수는 50으로 보정). 최신순(생성시각 desc). " +
                          "PENDING_APPROVAL·REJECTED를 포함한 모든 상태를 반환합니다(감사 목적)."
    )
    @GetMapping("/{projectId}/settings/infrastructure/configuration/history")
    public List<ProjectInfrastructureChangeResponse> getInfrastructureConfigurationHistory(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @Parameter(description = "이력을 조회할 프로젝트 ID") @PathVariable Long projectId,
            @Parameter(description = "조회 개수. 기본 50, 최대 200") @RequestParam(required = false) Integer limit
    ) {
        return projectInfrastructureConfigurationService.getHistory(ownerUserId, projectId, limit).stream()
                .map(this::toInfrastructureChangeResponse)
                .toList();
    }

    @Operation(
            summary = "프로젝트 비용 추정 및 예산 조회",
            description = "저장된 인프라 구성 기준으로 정적 가격표(가정 기반 추정치이며 실시간 클라우드 요금이 아님)를 사용해 " +
                          "월 예상 비용을 매 요청마다 온더플라이로 계산합니다(계산 결과는 저장하지 않음). " +
                          "인프라가 구성되지 않았거나 CONNECTED 클라우드 연결이 선택되지 않은 프로젝트도 200으로 응답하며 " +
                          "costAvailable=false와 함께 추정 필드는 null/빈 배열로 표시합니다."
    )
    @GetMapping("/{projectId}/settings/cost-budget")
    public ProjectCostBudgetResponse getCostBudget(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @Parameter(description = "비용/예산을 조회할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return toCostBudgetResponse(projectCostBudgetService.get(ownerUserId, projectId));
    }

    @Operation(
            summary = "프로젝트 월 예산 설정",
            description = "월 예산 금액을 저장합니다(upsert, 멱등). 통화는 USD만 지원하며, 인프라가 구성되지 않은 " +
                          "프로젝트도 예산을 먼저 설정할 수 있습니다(design D5). 저장 직후 재계산된 비용/예산 상태를 " +
                          "GET과 동일한 shape로 함께 반환해 FE가 경고 상태를 즉시 갱신할 수 있습니다."
    )
    @PutMapping("/{projectId}/settings/cost-budget")
    public ProjectCostBudgetResponse updateCostBudget(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @Parameter(description = "예산을 설정할 프로젝트 ID") @PathVariable Long projectId,
            @Valid @RequestBody UpdateProjectBudgetRequest request
    ) {
        return toCostBudgetResponse(projectCostBudgetService.update(
                ownerUserId,
                projectId,
                request.monthlyBudgetAmount(),
                request.currency()
        ));
    }

    @Operation(
            summary = "프로젝트 월 예산 해제",
            description = "예산 설정을 제거합니다. 미설정 상태에서 호출해도 204입니다(infra settings의 clear와 동일한 멱등 시맨틱)."
    )
    @DeleteMapping("/{projectId}/settings/cost-budget")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearCostBudget(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @Parameter(description = "예산을 해제할 프로젝트 ID") @PathVariable Long projectId
    ) {
        projectCostBudgetService.clear(ownerUserId, projectId);
    }

    @Operation(
            summary = "프로젝트 Repository 설정 조회",
            description = "연결된 GitHub 저장소 정보와 기본 브랜치를 조회합니다. 저장소가 연결되지 않은 프로젝트도 " +
                          "200과 connected=false로 응답합니다. defaultBranch는 매 요청마다 GitHub에서 라이브 조회하므로 " +
                          "GitHub 왕복 지연(p95 약 500ms)이 추가되며, 조회에 실패하면 null로 degrade합니다."
    )
    @GetMapping("/{projectId}/settings/repository")
    public ProjectRepositorySettingsResponse getRepositorySettings(
            @Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
            @Parameter(description = "설정을 조회할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return projectMapper.toRepositorySettingsResponse(projectFacade.getRepositorySettings(ownerUserId, projectId));
    }

    private ProjectChatSettingsResponse toChatSettingsResponse(ProjectChatSettingsResult result) {
        return new ProjectChatSettingsResponse(
                result.projectId(),
                result.changeApprovalRequired(),
                result.deploymentApprovalRequired(),
                result.domainApprovalRequired(),
                result.infraApprovalRequired()
        );
    }

    private ProjectInfrastructureSettingsResponse toInfrastructureSettingsResponse(
            ProjectInfrastructureSettingsResult result
    ) {
        return new ProjectInfrastructureSettingsResponse(
                result.projectId(),
                result.cloudConnectionId(),
                result.provider(),
                result.displayName(),
                result.region(),
                result.status(),
                result.lastCheckedAt(),
                result.updatedAt()
        );
    }

    private ProjectInfrastructureConfigurationResponse toInfrastructureConfigurationResponse(
            ProjectInfrastructureConfigurationResult result
    ) {
        ProjectInfrastructureConfigurationResponse.Settings settings = result.settings() == null
                ? null
                : new ProjectInfrastructureConfigurationResponse.Settings(
                        result.settings().deploymentArchitecture(),
                        result.settings().computeTier(),
                        result.settings().storageType(),
                        result.settings().networkAccess(),
                        result.settings().updatedAt()
                );
        ProjectInfrastructureConfigurationResponse.PendingChange pendingChange = result.pendingChange() == null
                ? null
                : new ProjectInfrastructureConfigurationResponse.PendingChange(
                        result.pendingChange().changeId(),
                        result.pendingChange().approvalId(),
                        result.pendingChange().action(),
                        result.pendingChange().deploymentArchitecture(),
                        result.pendingChange().computeTier(),
                        result.pendingChange().storageType(),
                        result.pendingChange().networkAccess(),
                        result.pendingChange().createdAt()
                );
        return new ProjectInfrastructureConfigurationResponse(
                result.projectId(),
                result.configurable(),
                settings,
                pendingChange
        );
    }

    private ProjectCostBudgetResponse toCostBudgetResponse(ProjectCostBudgetResult result) {
        List<ProjectCostBudgetResponse.ResourceCostResponse> resourceCosts = result.resourceCosts().stream()
                .map(item -> new ProjectCostBudgetResponse.ResourceCostResponse(
                        item.resourceType(), item.description(), item.monthlyCost()))
                .toList();
        ProjectCostBudgetResponse.BudgetResponse budget = result.budget() == null
                ? null
                : new ProjectCostBudgetResponse.BudgetResponse(
                        result.budget().monthlyBudgetAmount(),
                        result.budget().currency(),
                        result.budget().updatedAt()
                );
        return new ProjectCostBudgetResponse(
                result.projectId(),
                result.costAvailable(),
                result.provider(),
                result.currency(),
                result.estimatedMonthlyCost(),
                resourceCosts,
                result.assumptions(),
                result.priceTableVersion(),
                budget,
                result.budgetStatus(),
                result.budgetUsagePercent()
        );
    }

    private ProjectInfrastructureChangeResponse toInfrastructureChangeResponse(
            ProjectInfrastructureChangeResult result
    ) {
        return new ProjectInfrastructureChangeResponse(
                result.changeId(),
                result.action(),
                result.status(),
                result.deploymentArchitecture(),
                result.computeTier(),
                result.storageType(),
                result.networkAccess(),
                result.approvalId(),
                result.actorUserId(),
                result.createdAt(),
                result.decidedAt()
        );
    }
}
