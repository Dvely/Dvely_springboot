package com.example.dvely.project.infrastructure.mapper;

import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.presentation.dto.response.ProjectSummaryResponse;
public class ProjectMapper {

    public ProjectSummaryResponse toSummary(Project project) {
        return new ProjectSummaryResponse(
                project.getId(),
                project.getName(),
                project.getStatus().name()
        );
    }
}