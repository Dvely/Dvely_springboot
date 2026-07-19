package com.example.dvely.project.infrastructure.persistence.entity;

import com.example.dvely.project.domain.model.ProjectInfrastructureSettingChange;
import com.example.dvely.project.domain.value.ComputeTier;
import com.example.dvely.project.domain.value.DeploymentArchitecture;
import com.example.dvely.project.domain.value.InfrastructureChangeAction;
import com.example.dvely.project.domain.value.InfrastructureChangeStatus;
import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import com.example.dvely.project.domain.value.NetworkAccess;
import com.example.dvely.project.domain.value.StorageType;
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

/**
 * JPA row for {@code project_infrastructure_setting_changes} (V25 migration). Only
 * {@code @CreationTimestamp} is used (no {@code @UpdateTimestamp}) because {@code decided_at} is
 * an explicit business timestamp set by {@code ProjectInfrastructureSettingChange#decide} — not
 * "whenever this row was last touched" — so it is a plain {@code @Column} copied in
 * {@link #updateFrom}, matching the design's validate checklist (§2).
 */
@Entity
@Table(name = "project_infrastructure_setting_changes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectInfrastructureSettingChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "change_id")
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "deployment_architecture", nullable = false, length = 20)
    private String deploymentArchitecture;

    @Column(name = "compute_tier", nullable = false, length = 20)
    private String computeTier;

    @Column(name = "storage_type", nullable = false, length = 30)
    private String storageType;

    @Column(name = "network_access", nullable = false, length = 20)
    private String networkAccess;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    private ProjectInfrastructureSettingChangeEntity(Long projectId,
                                                      String action,
                                                      String status,
                                                      InfrastructureConfiguration configuration,
                                                      Long approvalId,
                                                      Long actorUserId,
                                                      LocalDateTime decidedAt) {
        this.projectId = projectId;
        this.action = action;
        this.status = status;
        this.deploymentArchitecture = configuration.deploymentArchitecture().name();
        this.computeTier = configuration.computeTier().name();
        this.storageType = configuration.storageType().name();
        this.networkAccess = configuration.networkAccess().name();
        this.approvalId = approvalId;
        this.actorUserId = actorUserId;
        this.decidedAt = decidedAt;
    }

    public static ProjectInfrastructureSettingChangeEntity from(ProjectInfrastructureSettingChange change) {
        return new ProjectInfrastructureSettingChangeEntity(
                change.getProjectId(),
                change.getAction().name(),
                change.getStatus().name(),
                change.getConfiguration(),
                change.getApprovalId(),
                change.getActorUserId(),
                change.getDecidedAt()
        );
    }

    /**
     * Only status/decidedAt are copied — action/configuration/approvalId/actorUserId are set
     * once at creation and never revised (the row's snapshot must stay exactly what was
     * requested/decided at that point, see class doc).
     */
    public void updateFrom(ProjectInfrastructureSettingChange change) {
        this.status = change.getStatus().name();
        this.decidedAt = change.getDecidedAt();
    }

    public ProjectInfrastructureSettingChange toDomain() {
        return new ProjectInfrastructureSettingChange(
                id,
                projectId,
                InfrastructureChangeAction.valueOf(action),
                InfrastructureChangeStatus.valueOf(status),
                new InfrastructureConfiguration(
                        DeploymentArchitecture.valueOf(deploymentArchitecture),
                        ComputeTier.valueOf(computeTier),
                        StorageType.valueOf(storageType),
                        NetworkAccess.valueOf(networkAccess)
                ),
                approvalId,
                actorUserId,
                createdAt,
                decidedAt
        );
    }
}
