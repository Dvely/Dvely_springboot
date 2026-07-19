package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.infrastructure.persistence.entity.ProjectBudgetSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProjectBudgetSettingRepository
        extends JpaRepository<ProjectBudgetSettingEntity, Long> {
}
