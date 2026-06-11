package com.example.dvely.project.presentation;

import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.command.dto.ConnectProjectRepositoryCommand;
import com.example.dvely.project.application.command.dto.UpdateProjectCommand;
import com.example.dvely.project.application.command.dto.ProjectDeleteMode;
import com.example.dvely.project.application.facade.ProjectFacade;
import com.example.dvely.project.application.result.ProjectChatSettingsResult;
import com.example.dvely.project.application.service.ProjectChatSettingsService;
import com.example.dvely.project.application.result.ProjectInfrastructureSettingsResult;
import com.example.dvely.project.application.service.ProjectInfrastructureSettingsService;
import com.example.dvely.project.infrastructure.mapper.ProjectMapper;
import com.example.dvely.project.presentation.dto.request.ConnectProjectRepositoryRequest;
import com.example.dvely.project.presentation.dto.request.CreateProjectRequest;
import com.example.dvely.project.presentation.dto.request.UpdateProjectRequest;
import com.example.dvely.project.presentation.dto.request.UpdateProjectChatSettingsRequest;
import com.example.dvely.project.presentation.dto.request.UpdateProjectInfrastructureSettingsRequest;
import com.example.dvely.project.presentation.dto.response.GithubRepositoryResponse;
import com.example.dvely.project.presentation.dto.response.ProjectActivityLogResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCommitResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCreateResponse;
import com.example.dvely.project.presentation.dto.response.ProjectDetailResponse;
import com.example.dvely.project.presentation.dto.response.ProjectOverviewResponse;
import com.example.dvely.project.presentation.dto.response.ProjectRepositoryResponse;
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

    @Operation(
            summary = "프로젝트 생성",
            description = "프로젝트 기본 메타데이터를 DRAFT 상태로 생성합니다. " +
                          "GitHub 저장소 생성 또는 기존 저장소 연결은 프로젝트 생성 후 " +
                          "/api/v1/projects/{projectId}/repository 엔드포인트에서 별도로 처리합니다."
    )
    @PostMapping
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
            description = "프로젝트 개요 화면에 필요한 현재 URL, 배포 상태, 최신 버전, 최근 변경, 최신 커밋, 저장소 상태 요약을 조회합니다."
    )
    @GetMapping("/{projectId}/overview")
    public ProjectOverviewResponse getOverview(@Parameter(hidden = true) @AuthenticationPrincipal Long ownerUserId,
                                               @Parameter(description = "개요를 조회할 프로젝트 ID") @PathVariable Long projectId) {
        var result = projectFacade.getOverview(ownerUserId, projectId);
        return projectMapper.toOverviewResponse(result);
    }

    @Operation(
            summary = "프로젝트 활동 로그 조회",
            description = "프로젝트 생성, 저장소 연결, 상태 변경 등 프로젝트 주요 활동 로그를 최신순으로 조회합니다."
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
                result.updatedAt()
        );
    }
}
