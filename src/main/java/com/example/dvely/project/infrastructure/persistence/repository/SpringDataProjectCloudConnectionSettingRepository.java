package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.infrastructure.persistence.entity.ProjectCloudConnectionSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProjectCloudConnectionSettingRepository
        extends JpaRepository<ProjectCloudConnectionSettingEntity, Long> {
}
