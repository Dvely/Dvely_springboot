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
    private String repositoryHeadSha;
    private String repositoryHeadMessage;
    private String repositoryHeadAuthor;
    private LocalDateTime repositoryHeadCommittedAt;
    private LocalDateTime repositoryHeadSyncedAt;
    private String repositoryVersion;
    private LocalDateTime repositoryVersionSyncedAt;
    private LocalDateTime repositoryConnectedAt;
    private boolean deleted;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    // I45: read-time snapshot of ProjectEntity's @Version, carried by the domain model so
    // ProjectRepositoryAdapter#save can detect a lost-update even outside a Spring-managed
    // transaction (where the adapter's own findById re-reads fresh DB state instead of hitting
    // the L1 cache — see the adapter's javadoc for the full case A/B analysis). Null for a
    // not-yet-persisted Project or one built through a legacy fixture constructor; the adapter
    // treats null as "skip the version guard" (nothing to compare against).
    private final Long version;

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
        this(
                id,
                ownerUserId,
                name,
                status,
                startMode,
                templateType,
                draftMode,
                deployStatus,
                currentUrl,
                currentVersion,
                sourceRepository,
                deploymentRepository,
                repositoryVisibility,
                repositoryBindingStatus,
                repositoryHealthStatus,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                deleted,
                createdAt,
                updatedAt,
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
                   String repositoryHeadSha,
                   String repositoryHeadMessage,
                   String repositoryHeadAuthor,
                   LocalDateTime repositoryHeadCommittedAt,
                   LocalDateTime repositoryHeadSyncedAt,
                   String repositoryVersion,
                   LocalDateTime repositoryVersionSyncedAt,
                   LocalDateTime repositoryConnectedAt,
                   boolean deleted,
                   LocalDateTime createdAt,
                   LocalDateTime updatedAt,
                   Long version) {
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
        this.repositoryHeadSha = repositoryHeadSha;
        this.repositoryHeadMessage = repositoryHeadMessage;
        this.repositoryHeadAuthor = repositoryHeadAuthor;
        this.repositoryHeadCommittedAt = repositoryHeadCommittedAt;
        this.repositoryHeadSyncedAt = repositoryHeadSyncedAt;
        this.repositoryVersion = repositoryVersion;
        this.repositoryVersionSyncedAt = repositoryVersionSyncedAt;
        this.repositoryConnectedAt = repositoryConnectedAt;
        this.deleted = deleted;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
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

    public String getRepositoryHeadSha() {
        return repositoryHeadSha;
    }

    public String getRepositoryHeadMessage() {
        return repositoryHeadMessage;
    }

    public String getRepositoryHeadAuthor() {
        return repositoryHeadAuthor;
    }

    public LocalDateTime getRepositoryHeadCommittedAt() {
        return repositoryHeadCommittedAt;
    }

    public LocalDateTime getRepositoryHeadSyncedAt() {
        return repositoryHeadSyncedAt;
    }

    public String getRepositoryVersion() {
        return repositoryVersion;
    }

    public LocalDateTime getRepositoryVersionSyncedAt() {
        return repositoryVersionSyncedAt;
    }

    public LocalDateTime getRepositoryConnectedAt() {
        return repositoryConnectedAt;
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

    public Long getVersion() {
        return version;
    }

    public void rename(String newName) {
        this.name = requireText(newName, "name");
    }

    public void changeStatus(ProjectStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public void bindRepository(String sourceRepository, RepositoryVisibility visibility) {
        this.sourceRepository = requireText(sourceRepository, "sourceRepository");
        this.deploymentRepository = this.sourceRepository;
        this.repositoryVisibility = Objects.requireNonNull(visibility, "repositoryVisibility must not be null");
        this.repositoryBindingStatus = RepositoryBindingStatus.BOUND;
        this.repositoryConnectedAt = LocalDateTime.now();
    }

    /**
     * Disconnects the GitHub repository from this project without touching GitHub itself
     * (no API call happens here — see {@code GithubRepositoryPort} usage at the call site,
     * which must remain absent by design). Only repository-scoped fields are cleared;
     * deployment artifact fields ({@code deployStatus}/{@code currentUrl}/{@code currentVersion})
     * are intentionally preserved because the previously deployed GitHub Pages site keeps
     * existing after this call — clearing them would misrepresent reality.
     * Throws if the project has no repository connected, mirroring the "already connected"
     * 409 guard on {@code connectRepository}.
     */
    public void unbindRepository() {
        if (!hasSourceRepository()) {
            throw new IllegalStateException("프로젝트에 연결된 저장소가 없습니다.");
        }
        this.sourceRepository = null;
        this.deploymentRepository = null;
        this.repositoryVisibility = RepositoryVisibility.PRIVATE;
        this.repositoryBindingStatus = RepositoryBindingStatus.NOT_BOUND;
        this.repositoryHealthStatus = RepositoryHealthStatus.UNKNOWN_ERROR;
        this.repositoryHeadSha = null;
        this.repositoryHeadMessage = null;
        this.repositoryHeadAuthor = null;
        this.repositoryHeadCommittedAt = null;
        // Clearing the head-sync timestamp prevents a re-connected (different) repository
        // from inheriting a stale "last synced" marker before its own webhook events arrive.
        this.repositoryHeadSyncedAt = null;
        this.repositoryVersion = null;
        this.repositoryVersionSyncedAt = null;
        this.repositoryConnectedAt = null;
    }

    public void updateRepositoryHealth(RepositoryHealthStatus healthStatus) {
        this.repositoryHealthStatus = Objects.requireNonNull(healthStatus, "healthStatus must not be null");
    }

    public void synchronizeRepositoryHead(String sha,
                                          String message,
                                          String author,
                                          LocalDateTime committedAt,
                                          LocalDateTime receivedAt) {
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        if (repositoryHeadSyncedAt != null && repositoryHeadSyncedAt.isAfter(receivedAt)) {
            return;
        }
        this.repositoryHeadSha = requireText(sha, "sha");
        this.repositoryHeadMessage = message;
        this.repositoryHeadAuthor = author;
        this.repositoryHeadCommittedAt = committedAt;
        this.repositoryHeadSyncedAt = receivedAt;
        this.repositoryHealthStatus = RepositoryHealthStatus.HEALTHY;
    }

    public void synchronizeRepositoryVersion(String version, LocalDateTime receivedAt) {
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        if (repositoryVersionSyncedAt != null && repositoryVersionSyncedAt.isAfter(receivedAt)) {
            return;
        }
        String normalizedVersion = requireText(version, "version");
        if (repositoryVersion != null
                && sequentialVersion(repositoryVersion) > sequentialVersion(normalizedVersion)) {
            return;
        }
        this.repositoryVersion = normalizedVersion;
        this.repositoryVersionSyncedAt = receivedAt;
        this.repositoryHealthStatus = RepositoryHealthStatus.HEALTHY;
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

    private static int sequentialVersion(String version) {
        if (version == null || !version.matches("v\\d+")) {
            return -1;
        }
        try {
            return Integer.parseInt(version.substring(1));
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }
}
