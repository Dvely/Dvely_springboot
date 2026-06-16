package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.infrastructure.persistence.entity.ProjectApprovalPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProjectApprovalPolicyRepository
        extends JpaRepository<ProjectApprovalPolicyEntity, Long> {
}
