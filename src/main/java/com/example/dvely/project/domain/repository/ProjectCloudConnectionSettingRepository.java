package com.example.dvely.project.domain.repository;

import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import java.util.Optional;

public interface ProjectCloudConnectionSettingRepository {

    ProjectCloudConnectionSetting save(ProjectCloudConnectionSetting setting);

    Optional<ProjectCloudConnectionSetting> findByProjectId(Long projectId);

    void deleteByProjectId(Long projectId);
}
