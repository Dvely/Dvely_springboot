package com.example.dvely.project.application.command;

import com.example.dvely.project.presentation.dto.request.CreateProjectRequest;
import com.example.dvely.project.presentation.dto.response.ProjectSummaryResponse;

public class ProjectCommandService {

    public ProjectSummaryResponse createProject(CreateProjectRequest request) {
        return new ProjectSummaryResponse(null, request.name(), "DRAFT");
    }
}
