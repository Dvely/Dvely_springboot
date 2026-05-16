package com.example.dvely.project.presentation;

import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.command.dto.ConnectProjectRepositoryCommand;
import com.example.dvely.project.application.command.dto.UpdateProjectCommand;
import com.example.dvely.project.application.command.dto.ProjectDeleteMode;
import com.example.dvely.project.application.facade.ProjectFacade;
import com.example.dvely.project.infrastructure.mapper.ProjectMapper;
import com.example.dvely.project.presentation.dto.request.ConnectProjectRepositoryRequest;
import com.example.dvely.project.presentation.dto.request.CreateProjectRequest;
import com.example.dvely.project.presentation.dto.request.UpdateProjectRequest;
import com.example.dvely.project.presentation.dto.response.GithubRepositoryResponse;
import com.example.dvely.project.presentation.dto.response.ProjectActivityLogResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCommitResponse;
import com.example.dvely.project.presentation.dto.response.ProjectCreateResponse;
import com.example.dvely.project.presentation.dto.response.ProjectDetailResponse;
import com.example.dvely.project.presentation.dto.response.ProjectOverviewResponse;
import com.example.dvely.project.presentation.dto.response.ProjectRepositoryResponse;
import com.example.dvely.project.presentation.dto.response.RepositoryHealthResponse;
import com.example.dvely.project.presentation.dto.response.ProjectSummaryResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectFacade projectFacade;
    private final ProjectMapper projectMapper;

    @PostMapping
    public ProjectCreateResponse createProject(@AuthenticationPrincipal Long ownerUserId,
                                               @Valid @RequestBody CreateProjectRequest request) {
        var result = projectFacade.createProject(ownerUserId, new CreateProjectCommand(
                request.name(),
                request.startMode(),
                request.templateType(),
                request.draftMode()
        ));
        return projectMapper.toCreateResponse(result);
    }

    @PostMapping("/{projectId}/repository")
    public ProjectRepositoryResponse connectRepository(@AuthenticationPrincipal Long ownerUserId,
                                                       @PathVariable Long projectId,
                                                       @Valid @RequestBody ConnectProjectRepositoryRequest request) {
        var result = projectFacade.connectRepository(ownerUserId, projectId, new ConnectProjectRepositoryCommand(
                request.repositoryMode(),
                request.repositoryName(),
                request.repositoryFullName(),
                request.repositoryVisibility()
        ));
        return projectMapper.toProjectRepositoryResponse(result);
    }

    @GetMapping("/github/repositories")
    public List<GithubRepositoryResponse> getGithubRepositories(@AuthenticationPrincipal Long ownerUserId) {
        return projectFacade.getGithubRepositories(ownerUserId).stream()
                .map(projectMapper::toGithubRepositoryResponse)
                .toList();
    }

    @GetMapping
    public List<ProjectSummaryResponse> getProjects(@AuthenticationPrincipal Long ownerUserId) {
        return projectFacade.getProjects(ownerUserId).stream()
                .map(projectMapper::toSummaryResponse)
                .toList();
    }

    @GetMapping("/{projectId}")
    public ProjectDetailResponse getProject(@AuthenticationPrincipal Long ownerUserId,
                                            @PathVariable Long projectId) {
        var result = projectFacade.getProject(ownerUserId, projectId);
        return projectMapper.toDetailResponse(result);
    }

    @PatchMapping("/{projectId}")
    public ProjectDetailResponse updateProject(@AuthenticationPrincipal Long ownerUserId,
                                               @PathVariable Long projectId,
                                               @Valid @RequestBody UpdateProjectRequest request) {
        var result = projectFacade.updateProject(ownerUserId, projectId, new UpdateProjectCommand(request.name()));
        return projectMapper.toDetailResponse(result);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@AuthenticationPrincipal Long ownerUserId,
                              @PathVariable Long projectId,
                              @RequestParam(name = "deleteMode", required = false) String deleteMode) {
        projectFacade.deleteProject(ownerUserId, projectId, ProjectDeleteMode.from(deleteMode));
    }

    @GetMapping("/{projectId}/overview")
    public ProjectOverviewResponse getOverview(@AuthenticationPrincipal Long ownerUserId,
                                               @PathVariable Long projectId) {
        var result = projectFacade.getOverview(ownerUserId, projectId);
        return projectMapper.toOverviewResponse(result);
    }

    @GetMapping("/{projectId}/activity-logs")
    public List<ProjectActivityLogResponse> getActivityLogs(@AuthenticationPrincipal Long ownerUserId,
                                                            @PathVariable Long projectId) {
        return projectFacade.getActivityLogs(ownerUserId, projectId).stream()
                .map(projectMapper::toActivityLogResponse)
                .toList();
    }

    @GetMapping("/{projectId}/commits")
    public List<ProjectCommitResponse> getCommits(@AuthenticationPrincipal Long ownerUserId,
                                                  @PathVariable Long projectId) {
        return projectFacade.getCommits(ownerUserId, projectId).stream()
                .map(projectMapper::toCommitResponse)
                .toList();
    }

    @GetMapping("/{projectId}/repository-health")
    public RepositoryHealthResponse getRepositoryHealth(@AuthenticationPrincipal Long ownerUserId,
                                                        @PathVariable Long projectId) {
        return projectMapper.toRepositoryHealthResponse(projectFacade.getRepositoryHealth(ownerUserId, projectId));
    }
}
