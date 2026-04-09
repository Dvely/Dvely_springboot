package com.example.dvely.project.domain.repository;

import com.example.dvely.project.domain.model.Project;
import java.util.Optional;

public interface ProjectRepository {

    Optional<Project> findById(Long projectId);

    Project save(Project project);
}
