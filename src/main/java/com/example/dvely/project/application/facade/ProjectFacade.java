package com.example.dvely.project.application.facade;

import com.example.dvely.project.application.command.ProjectCommandService;
import com.example.dvely.project.application.command.dto.ConnectProjectRepositoryCommand;
import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.command.dto.ProjectDeleteMode;
import com.example.dvely.project.application.command.dto.UpdateProjectCommand;
import com.example.dvely.project.application.query.ProjectQueryService;
import com.example.dvely.project.application.result.ActivityLogResult;
import com.example.dvely.project.application.result.CommitResult;
import com.example.dvely.project.application.result.GithubRepositoryResult;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectOverviewResult;
import com.example.dvely.project.application.result.ProjectCreationResult;
import com.example.dvely.project.application.result.ProjectRepositoryResult;
import com.example.dvely.project.application.result.ProjectRepositorySettingsResult;
import com.example.dvely.project.application.result.ProjectSummaryResult;
import com.example.dvely.project.application.result.RepositoryHealthResult;
import com.example.dvely.project.application.service.ProjectCreationService;
import com.example.dvely.project.application.service.ProjectRepositorySettingsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectFacade {

    private final ProjectCommandService projectCommandService;
    private final ProjectQueryService projectQueryService;
    private final ProjectCreationService projectCreationService;
    private final ProjectRepositorySettingsService projectRepositorySettingsService;

    public ProjectCreationResult createProject(Long ownerUserId, CreateProjectCommand command) {
        return projectCreationService.create(ownerUserId, command);
    }

    public ProjectRepositoryResult connectRepository(Long ownerUserId,
                                                     Long projectId,
                                                     ConnectProjectRepositoryCommand command) {
        return projectCommandService.connectRepository(ownerUserId, projectId, command);
    }

    public ProjectDetailResult updateProject(Long ownerUserId, Long projectId, UpdateProjectCommand command) {
        return projectCommandService.updateProject(ownerUserId, projectId, command);
    }

    public void deleteProject(Long ownerUserId, Long projectId, ProjectDeleteMode deleteMode) {
        projectCommandService.deleteProject(ownerUserId, projectId, deleteMode);
    }

    public List<GithubRepositoryResult> getGithubRepositories(Long ownerUserId) {
        return projectQueryService.getGithubRepositories(ownerUserId);
    }

    public List<ProjectSummaryResult> getProjects(Long ownerUserId) {
        return projectQueryService.getProjects(ownerUserId);
    }

    public ProjectDetailResult getProject(Long ownerUserId, Long projectId) {
        return projectQueryService.getProject(ownerUserId, projectId);
    }

    public ProjectOverviewResult getOverview(Long ownerUserId, Long projectId) {
        return projectQueryService.getOverview(ownerUserId, projectId);
    }

    public List<ActivityLogResult> getActivityLogs(Long ownerUserId, Long projectId) {
        return projectQueryService.getActivityLogs(ownerUserId, projectId);
    }

    public List<CommitResult> getCommits(Long ownerUserId, Long projectId) {
        return projectQueryService.getCommits(ownerUserId, projectId);
    }

    public RepositoryHealthResult getRepositoryHealth(Long ownerUserId, Long projectId) {
        return projectQueryService.getRepositoryHealth(ownerUserId, projectId);
    }

    public ProjectRepositorySettingsResult getRepositorySettings(Long ownerUserId, Long projectId) {
        return projectRepositorySettingsService.get(ownerUserId, projectId);
    }

    public void disconnectRepository(Long ownerUserId, Long projectId) {
        projectCommandService.disconnectRepository(ownerUserId, projectId);
    }
}
