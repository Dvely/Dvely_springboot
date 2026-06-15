package com.example.dvely.project.domain.repository;

import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import java.util.Optional;

public interface ProjectApprovalPolicyRepository {

    Optional<ProjectApprovalPolicy> findByProjectId(Long projectId);

    ProjectApprovalPolicy save(ProjectApprovalPolicy policy);
}
