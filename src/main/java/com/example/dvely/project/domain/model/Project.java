package com.example.dvely.project.domain.model;

import com.example.dvely.project.domain.value.ProjectStatus;
import java.util.Objects;

public class Project {

    private final Long id;
    private final String name;
    private ProjectStatus status;

    public Project(Long id, String name, ProjectStatus status) {
        this.id = id;
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public void changeStatus(ProjectStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }
}
