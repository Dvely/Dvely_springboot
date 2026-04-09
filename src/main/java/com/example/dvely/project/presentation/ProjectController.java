package com.example.dvely.project.presentation;

import com.example.dvely.project.application.command.ProjectCommandService;
import com.example.dvely.project.application.query.ProjectQueryService;
import com.example.dvely.project.presentation.dto.request.CreateProjectRequest;
import com.example.dvely.project.presentation.dto.response.ProjectSummaryResponse;
import java.util.List;

public class ProjectController {

    private final ProjectCommandService projectCommandService;
    private final ProjectQueryService projectQueryService;

    public ProjectController(ProjectCommandService projectCommandService,
                             ProjectQueryService projectQueryService) {
        this.projectCommandService = projectCommandService;
        this.projectQueryService = projectQueryService;
    }

    public ProjectSummaryResponse createProject(CreateProjectRequest request) {
        return projectCommandService.createProject(request);
    }

    public List<ProjectSummaryResponse> getProjects() {
        return projectQueryService.getProjects();
    }
}