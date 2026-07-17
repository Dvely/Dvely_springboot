package com.example.dvely.project.infrastructure.persistence.entity;

import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "project_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "project_name", nullable = false)
    private String name;

    @Column(name = "project_status", nullable = false)
    private String status;

    @Column(name = "start_mode", nullable = false)
    private String startMode;

    @Column(name = "template_type")
    private String templateType;

    @Column(name = "draft_mode", nullable = false)
    private String draftMode;

    @Column(name = "deploy_status", nullable = false)
    private String deployStatus;

    @Column(name = "current_url")
    private String currentUrl;

    @Column(name = "current_version")
    private String currentVersion;

    @Column(name = "source_repository")
    private String sourceRepository;

    @Column(name = "deployment_repository")
    private String deploymentRepository;

    @Column(name = "repository_visibility", nullable = false)
    private String repositoryVisibility;

    @Column(name = "binding_status", nullable = false)
    private String repositoryBindingStatus;

    @Column(name = "repository_health", nullable = false)
    private String repositoryHealthStatus;

    @Column(name = "repository_head_sha", length = 40)
    private String repositoryHeadSha;

    @Column(name = "repository_head_message", length = 1000)
    private String repositoryHeadMessage;

    @Column(name = "repository_head_author", length = 255)
    private String repositoryHeadAuthor;

    @Column(name = "repository_head_committed_at")
    private LocalDateTime repositoryHeadCommittedAt;

    @Column(name = "repository_head_synced_at")
    private LocalDateTime repositoryHeadSyncedAt;

    @Column(name = "repository_version", length = 100)
    private String repositoryVersion;

    @Column(name = "repository_version_synced_at")
    private LocalDateTime repositoryVersionSyncedAt;

    @Column(name = "repository_connected_at")
    private LocalDateTime repositoryConnectedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private ProjectEntity(Long ownerUserId,
                          String name,
                          String status,
                          String startMode,
                          String templateType,
                          String draftMode,
                          String deployStatus,
                          String currentUrl,
                          String currentVersion,
                          String sourceRepository,
                          String deploymentRepository,
                          String repositoryVisibility,
                          String repositoryBindingStatus,
                          String repositoryHealthStatus,
                          boolean deleted) {
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.status = status;
        this.startMode = startMode;
        this.templateType = templateType;
        this.draftMode = draftMode;
        this.deployStatus = deployStatus;
        this.currentUrl = currentUrl;
        this.currentVersion = currentVersion;
        this.sourceRepository = sourceRepository;
        this.deploymentRepository = deploymentRepository;
        this.repositoryVisibility = repositoryVisibility;
        this.repositoryBindingStatus = repositoryBindingStatus;
        this.repositoryHealthStatus = repositoryHealthStatus;
        this.deleted = deleted;
    }

    public static ProjectEntity from(Project project) {
        ProjectEntity entity = new ProjectEntity(
                project.getOwnerUserId(),
                project.getName(),
                project.getStatus().name(),
                project.getStartMode(),
                project.getTemplateType(),
                project.getDraftMode(),
                project.getDeployStatus().name(),
                project.getCurrentUrl(),
                project.getCurrentVersion(),
                project.getSourceRepository(),
                project.getDeploymentRepository(),
                project.getRepositoryVisibility().name(),
                project.getRepositoryBindingStatus().name(),
                project.getRepositoryHealthStatus().name(),
                project.isDeleted()
        );
        entity.repositoryHeadSha = project.getRepositoryHeadSha();
        entity.repositoryHeadMessage = project.getRepositoryHeadMessage();
        entity.repositoryHeadAuthor = project.getRepositoryHeadAuthor();
        entity.repositoryHeadCommittedAt = project.getRepositoryHeadCommittedAt();
        entity.repositoryHeadSyncedAt = project.getRepositoryHeadSyncedAt();
        entity.repositoryVersion = project.getRepositoryVersion();
        entity.repositoryVersionSyncedAt = project.getRepositoryVersionSyncedAt();
        entity.repositoryConnectedAt = project.getRepositoryConnectedAt();
        return entity;
    }

    public void updateFrom(Project project) {
        this.ownerUserId = project.getOwnerUserId();
        this.name = project.getName();
        this.status = project.getStatus().name();
        this.startMode = project.getStartMode();
        this.templateType = project.getTemplateType();
        this.draftMode = project.getDraftMode();
        this.deployStatus = project.getDeployStatus().name();
        this.currentUrl = project.getCurrentUrl();
        this.currentVersion = project.getCurrentVersion();
        this.sourceRepository = project.getSourceRepository();
        this.deploymentRepository = project.getDeploymentRepository();
        this.repositoryVisibility = project.getRepositoryVisibility().name();
        this.repositoryBindingStatus = project.getRepositoryBindingStatus().name();
        this.repositoryHealthStatus = project.getRepositoryHealthStatus().name();
        this.repositoryHeadSha = project.getRepositoryHeadSha();
        this.repositoryHeadMessage = project.getRepositoryHeadMessage();
        this.repositoryHeadAuthor = project.getRepositoryHeadAuthor();
        this.repositoryHeadCommittedAt = project.getRepositoryHeadCommittedAt();
        this.repositoryHeadSyncedAt = project.getRepositoryHeadSyncedAt();
        this.repositoryVersion = project.getRepositoryVersion();
        this.repositoryVersionSyncedAt = project.getRepositoryVersionSyncedAt();
        this.repositoryConnectedAt = project.getRepositoryConnectedAt();
        this.deleted = project.isDeleted();
    }

    public Project toDomain() {
        return new Project(
                id,
                ownerUserId,
                name,
                ProjectStatus.valueOf(status),
                startMode,
                templateType,
                draftMode,
                DeployStatus.valueOf(deployStatus),
                currentUrl,
                currentVersion,
                sourceRepository,
                deploymentRepository,
                RepositoryVisibility.valueOf(repositoryVisibility),
                RepositoryBindingStatus.valueOf(repositoryBindingStatus),
                RepositoryHealthStatus.valueOf(repositoryHealthStatus),
                repositoryHeadSha,
                repositoryHeadMessage,
                repositoryHeadAuthor,
                repositoryHeadCommittedAt,
                repositoryHeadSyncedAt,
                repositoryVersion,
                repositoryVersionSyncedAt,
                repositoryConnectedAt,
                deleted,
                createdAt,
                updatedAt
        );
    }
}
