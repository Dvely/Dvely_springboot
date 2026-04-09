package com.example.dvely.project.domain.service;

import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.value.ProjectStatus;

public class ProjectDomainService {

    public Project initialize(Long id, String name) {
        return new Project(id, name, ProjectStatus.DRAFT);
    }
}
