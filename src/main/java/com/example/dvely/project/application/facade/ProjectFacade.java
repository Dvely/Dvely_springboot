package com.example.dvely.project.application.facade;

import com.example.dvely.project.application.command.ProjectCommandService;
import com.example.dvely.project.application.command.dto.CreateProjectCommand;
import com.example.dvely.project.application.command.dto.CreateRepositoryBindingCommand;
import com.example.dvely.project.application.command.dto.UpdateProjectCommand;
import com.example.dvely.project.application.command.dto.UpdateRepositoryBindingCommand;
import com.example.dvely.project.application.query.ProjectQueryService;
import com.example.dvely.project.application.result.ActivityLogResult;
import com.example.dvely.project.application.result.CommitResult;
import com.example.dvely.project.application.result.ProjectDetailResult;
import com.example.dvely.project.application.result.ProjectOverviewResult;
import com.example.dvely.project.application.result.ProjectSummaryResult;
import com.example.dvely.project.application.result.RepositoryBindingResult;
import com.example.dvely.project.application.result.RepositoryHealthResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectFacade {

    private final ProjectCommandService projectCommandService;
    private final ProjectQueryService projectQueryService;

    public ProjectDetailResult createProject(Long ownerUserId, CreateProjectCommand command) {
        return projectCommandService.createProject(ownerUserId, command);
    }

    public ProjectDetailResult updateProject(Long ownerUserId, Long projectId, UpdateProjectCommand command) {
        return projectCommandService.updateProject(ownerUserId, projectId, command);
    }

    public void deleteProject(Long ownerUserId, Long projectId) {
        projectCommandService.deleteProject(ownerUserId, projectId);
    }

    public RepositoryBindingResult createRepositoryBinding(Long ownerUserId,
                                                           Long projectId,
                                                           CreateRepositoryBindingCommand command) {
        return projectCommandService.createRepositoryBinding(ownerUserId, projectId, command);
    }

    public RepositoryBindingResult updateRepositoryBinding(Long ownerUserId,
                                                           Long projectId,
                                                           UpdateRepositoryBindingCommand command) {
        return projectCommandService.updateRepositoryBinding(ownerUserId, projectId, command);
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

    public RepositoryBindingResult getRepositoryBinding(Long ownerUserId, Long projectId) {
        return projectQueryService.getRepositoryBinding(ownerUserId, projectId);
    }

    public RepositoryHealthResult getRepositoryHealth(Long ownerUserId, Long projectId) {
        return projectQueryService.getRepositoryHealth(ownerUserId, projectId);
    }
}
