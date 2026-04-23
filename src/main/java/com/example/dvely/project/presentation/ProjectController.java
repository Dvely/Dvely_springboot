package com.example.dvely.project.presentation;

import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.command.dto.CreateRepositoryBindingCommand;
import com.example.dvely.project.application.command.dto.UpdateProjectCommand;
import com.example.dvely.project.application.command.dto.UpdateRepositoryBindingCommand;
import com.example.dvely.project.application.facade.ProjectFacade;
import com.example.dvely.project.infrastructure.mapper.ProjectMapper;
import com.example.dvely.project.presentation.dto.request.CreateProjectRequest;
import com.example.dvely.project.presentation.dto.request.CreateRepositoryBindingRequest;
import com.example.dvely.project.presentation.dto.request.UpdateProjectRequest;
import com.example.dvely.project.presentation.dto.request.UpdateRepositoryBindingRequest;
import com.example.dvely.project.presentation.dto.response.ProjectActivityLogResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCommitResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCreateResponse;
import com.example.dvely.project.presentation.dto.response.ProjectDetailResponse;
import com.example.dvely.project.presentation.dto.response.ProjectOverviewResponse;
import com.example.dvely.project.presentation.dto.response.RepositoryBindingResponse;
import com.example.dvely.project.presentation.dto.response.RepositoryHealthResponse;
import com.example.dvely.project.presentation.dto.response.ProjectSummaryResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    // TODO: auth/user 모듈의 인증 컨텍스트 도입 후 헤더 기반 식별을 교체한다.
    private static final String USER_ID_HEADER = "X-USER-ID";

    private final ProjectFacade projectFacade;
    private final ProjectMapper projectMapper;

    @PostMapping
    public ProjectCreateResponse createProject(@RequestHeader(USER_ID_HEADER) Long ownerUserId,
                                               @Valid @RequestBody CreateProjectRequest request) {
        var result = projectFacade.createProject(ownerUserId, new CreateProjectCommand(
                request.name(),
                request.startMode(),
                request.templateType(),
                request.draftMode(),
                request.repositoryVisibility()
        ));
        return projectMapper.toCreateResponse(result);
    }

    @GetMapping
    public List<ProjectSummaryResponse> getProjects(@RequestHeader(USER_ID_HEADER) Long ownerUserId) {
        return projectFacade.getProjects(ownerUserId).stream()
                .map(projectMapper::toSummaryResponse)
                .toList();
    }

    @GetMapping("/{projectId}")
    public ProjectDetailResponse getProject(@RequestHeader(USER_ID_HEADER) Long ownerUserId,
                                            @PathVariable Long projectId) {
        var result = projectFacade.getProject(ownerUserId, projectId);
        return projectMapper.toDetailResponse(result);
    }

    @PatchMapping("/{projectId}")
    public ProjectDetailResponse updateProject(@RequestHeader(USER_ID_HEADER) Long ownerUserId,
                                               @PathVariable Long projectId,
                                               @Valid @RequestBody UpdateProjectRequest request) {
        var result = projectFacade.updateProject(ownerUserId, projectId, new UpdateProjectCommand(request.name()));
        return projectMapper.toDetailResponse(result);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@RequestHeader(USER_ID_HEADER) Long ownerUserId,
                              @PathVariable Long projectId) {
        projectFacade.deleteProject(ownerUserId, projectId);
    }

    @GetMapping("/{projectId}/overview")
    public ProjectOverviewResponse getOverview(@RequestHeader(USER_ID_HEADER) Long ownerUserId,
                                               @PathVariable Long projectId) {
        var result = projectFacade.getOverview(ownerUserId, projectId);
        return projectMapper.toOverviewResponse(result);
    }

    @GetMapping("/{projectId}/activity-logs")
    public List<ProjectActivityLogResponse> getActivityLogs(@RequestHeader(USER_ID_HEADER) Long ownerUserId,
                                                            @PathVariable Long projectId) {
        return projectFacade.getActivityLogs(ownerUserId, projectId).stream()
                .map(projectMapper::toActivityLogResponse)
                .toList();
    }

    @GetMapping("/{projectId}/commits")
    public List<ProjectCommitResponse> getCommits(@RequestHeader(USER_ID_HEADER) Long ownerUserId,
                                                  @PathVariable Long projectId) {
        return projectFacade.getCommits(ownerUserId, projectId).stream()
                .map(projectMapper::toCommitResponse)
                .toList();
    }

    @GetMapping("/{projectId}/repository-binding")
    public RepositoryBindingResponse getRepositoryBinding(@RequestHeader(USER_ID_HEADER) Long ownerUserId,
                                                          @PathVariable Long projectId) {
        return projectMapper.toRepositoryBindingResponse(projectFacade.getRepositoryBinding(ownerUserId, projectId));
    }

    @PostMapping("/{projectId}/repository-binding")
    public RepositoryBindingResponse createRepositoryBinding(@RequestHeader(USER_ID_HEADER) Long ownerUserId,
                                                             @PathVariable Long projectId,
                                                             @Valid @RequestBody CreateRepositoryBindingRequest request) {
        var result = projectFacade.createRepositoryBinding(
                ownerUserId,
                projectId,
                new CreateRepositoryBindingCommand(
                        request.bindingType(),
                        request.repositoryFullName(),
                        request.repositoryName(),
                        request.visibility()
                )
        );
        return projectMapper.toRepositoryBindingResponse(result);
    }

    @PatchMapping("/{projectId}/repository-binding")
    public RepositoryBindingResponse updateRepositoryBinding(@RequestHeader(USER_ID_HEADER) Long ownerUserId,
                                                             @PathVariable Long projectId,
                                                             @RequestBody UpdateRepositoryBindingRequest request) {
        var result = projectFacade.updateRepositoryBinding(
                ownerUserId,
                projectId,
                new UpdateRepositoryBindingCommand(request.deploymentRepository(), request.visibility())
        );
        return projectMapper.toRepositoryBindingResponse(result);
    }

    @GetMapping("/{projectId}/repository-health")
    public RepositoryHealthResponse getRepositoryHealth(@RequestHeader(USER_ID_HEADER) Long ownerUserId,
                                                        @PathVariable Long projectId) {
        return projectMapper.toRepositoryHealthResponse(projectFacade.getRepositoryHealth(ownerUserId, projectId));
    }
}