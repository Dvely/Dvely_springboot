package com.example.dvely.project.domain.model;

import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import com.example.dvely.project.domain.value.ProjectStatus;
import java.time.LocalDateTime;
import java.util.Objects;

public class Project {

    private final Long id;
    private final Long ownerUserId;
    private String name;
    private ProjectStatus status;
    private final String startMode;
    private final String templateType;
    private final String draftMode;
    private DeployStatus deployStatus;
    private String currentUrl;
    private String currentVersion;
    private String sourceRepository;
    private String deploymentRepository;
    private RepositoryVisibility repositoryVisibility;
    private RepositoryBindingStatus repositoryBindingStatus;
    private RepositoryHealthStatus repositoryHealthStatus;
    private boolean deleted;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public Project(Long ownerUserId,
                   String name,
                   String startMode,
                   String templateType,
                   String draftMode,
                   RepositoryVisibility repositoryVisibility) {
        this(
                null,
                ownerUserId,
                name,
                ProjectStatus.DRAFT,
                requireText(startMode, "startMode"),
                templateType,
                requireText(draftMode, "draftMode"),
                DeployStatus.DRAFT,
                null,
                null,
                null,
                null,
                repositoryVisibility == null ? RepositoryVisibility.PRIVATE : repositoryVisibility,
                RepositoryBindingStatus.NOT_BOUND,
                RepositoryHealthStatus.UNKNOWN_ERROR,
                false,
                null,
                null
        );
    }

    public Project(Long id,
                   Long ownerUserId,
                   String name,
                   ProjectStatus status,
                   String startMode,
                   String templateType,
                   String draftMode,
                   DeployStatus deployStatus,
                   String currentUrl,
                   String currentVersion,
                   String sourceRepository,
                   String deploymentRepository,
                   RepositoryVisibility repositoryVisibility,
                   RepositoryBindingStatus repositoryBindingStatus,
                   RepositoryHealthStatus repositoryHealthStatus,
                   boolean deleted,
                   LocalDateTime createdAt,
                   LocalDateTime updatedAt) {
        this.id = id;
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.startMode = Objects.requireNonNull(startMode, "startMode must not be null");
        this.templateType = templateType;
        this.draftMode = Objects.requireNonNull(draftMode, "draftMode must not be null");
        this.deployStatus = Objects.requireNonNull(deployStatus, "deployStatus must not be null");
        this.currentUrl = currentUrl;
        this.currentVersion = currentVersion;
        this.sourceRepository = sourceRepository;
        this.deploymentRepository = deploymentRepository;
        this.repositoryVisibility = Objects.requireNonNull(repositoryVisibility, "repositoryVisibility must not be null");
        this.repositoryBindingStatus = Objects.requireNonNull(repositoryBindingStatus, "repositoryBindingStatus must not be null");
        this.repositoryHealthStatus = Objects.requireNonNull(repositoryHealthStatus, "repositoryHealthStatus must not be null");
        this.deleted = deleted;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public String getName() {
        return name;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public String getStartMode() {
        return startMode;
    }

    public String getTemplateType() {
        return templateType;
    }

    public String getDraftMode() {
        return draftMode;
    }

    public DeployStatus getDeployStatus() {
        return deployStatus;
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public String getSourceRepository() {
        return sourceRepository;
    }

    public String getDeploymentRepository() {
        return deploymentRepository;
    }

    public RepositoryVisibility getRepositoryVisibility() {
        return repositoryVisibility;
    }

    public RepositoryBindingStatus getRepositoryBindingStatus() {
        return repositoryBindingStatus;
    }

    public RepositoryHealthStatus getRepositoryHealthStatus() {
        return repositoryHealthStatus;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void rename(String newName) {
        this.name = requireText(newName, "name");
    }

    public void changeStatus(ProjectStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public void bindRepository(String sourceRepository,
                               String deploymentRepository,
                               RepositoryVisibility visibility) {
        this.sourceRepository = requireText(sourceRepository, "sourceRepository");
        this.deploymentRepository = requireText(deploymentRepository, "deploymentRepository");
        this.repositoryVisibility = Objects.requireNonNull(visibility, "repositoryVisibility must not be null");
        this.repositoryBindingStatus = RepositoryBindingStatus.BOUND;
    }

    public void updateRepositoryBinding(String deploymentRepository, RepositoryVisibility visibility) {
        if (repositoryBindingStatus != RepositoryBindingStatus.BOUND) {
            throw new IllegalStateException("Repository is not bound yet.");
        }

        if (deploymentRepository != null && !deploymentRepository.isBlank()) {
            this.deploymentRepository = deploymentRepository.trim();
        }
        if (visibility != null) {
            this.repositoryVisibility = visibility;
        }
    }

    public void updateRepositoryHealth(RepositoryHealthStatus healthStatus) {
        this.repositoryHealthStatus = Objects.requireNonNull(healthStatus, "healthStatus must not be null");
    }

    public void updateDeployment(DeployStatus deployStatus, String currentUrl, String currentVersion) {
        this.deployStatus = Objects.requireNonNull(deployStatus, "deployStatus must not be null");
        this.currentUrl = currentUrl;
        this.currentVersion = currentVersion;
    }

    public void softDelete() {
        this.status = ProjectStatus.ARCHIVED;
        this.deleted = true;
    }

    public boolean hasSourceRepository() {
        return sourceRepository != null && !sourceRepository.isBlank();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
