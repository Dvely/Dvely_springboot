package com.example.dvely.project.infrastructure.persistence.entity;

import com.example.dvely.project.domain.model.ProjectInfrastructureSetting;
import com.example.dvely.project.domain.value.ComputeTier;
import com.example.dvely.project.domain.value.DeploymentArchitecture;
import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import com.example.dvely.project.domain.value.NetworkAccess;
import com.example.dvely.project.domain.value.StorageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * JPA row for {@code project_infrastructure_settings} (V25 migration) — the currently applied
 * configuration, one row per project (project_id is both PK and FK, no surrogate id, mirroring
 * {@code ProjectCloudConnectionSettingEntity}). Enum columns are stored as plain VARCHAR + manual
 * {@code valueOf} conversion rather than {@code @Enumerated} (approval/cloudconnection precedent
 * in this codebase — keeps DB values human-readable and independent of enum ordinal order).
 */
@Entity
@Table(name = "project_infrastructure_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectInfrastructureSettingEntity {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "deployment_architecture", nullable = false, length = 20)
    private String deploymentArchitecture;

    @Column(name = "compute_tier", nullable = false, length = 20)
    private String computeTier;

    @Column(name = "storage_type", nullable = false, length = 30)
    private String storageType;

    @Column(name = "network_access", nullable = false, length = 20)
    private String networkAccess;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private ProjectInfrastructureSettingEntity(ProjectInfrastructureSetting setting) {
        this.projectId = setting.getProjectId();
        applyConfiguration(setting.getConfiguration());
    }

    public static ProjectInfrastructureSettingEntity from(ProjectInfrastructureSetting setting) {
        return new ProjectInfrastructureSettingEntity(setting);
    }

    public void updateFrom(ProjectInfrastructureSetting setting) {
        applyConfiguration(setting.getConfiguration());
    }

    private void applyConfiguration(InfrastructureConfiguration configuration) {
        this.deploymentArchitecture = configuration.deploymentArchitecture().name();
        this.computeTier = configuration.computeTier().name();
        this.storageType = configuration.storageType().name();
        this.networkAccess = configuration.networkAccess().name();
    }

    public ProjectInfrastructureSetting toDomain() {
        return new ProjectInfrastructureSetting(
                projectId,
                new InfrastructureConfiguration(
                        DeploymentArchitecture.valueOf(deploymentArchitecture),
                        ComputeTier.valueOf(computeTier),
                        StorageType.valueOf(storageType),
                        NetworkAccess.valueOf(networkAccess)
                ),
                createdAt,
                updatedAt
        );
    }
}
