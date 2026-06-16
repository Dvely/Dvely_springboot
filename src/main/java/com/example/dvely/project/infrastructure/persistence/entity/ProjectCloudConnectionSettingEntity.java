package com.example.dvely.project.infrastructure.persistence.entity;

import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
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

@Entity
@Table(name = "project_cloud_connection_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectCloudConnectionSettingEntity {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "cloud_connection_id", nullable = false)
    private Long cloudConnectionId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private ProjectCloudConnectionSettingEntity(ProjectCloudConnectionSetting setting) {
        this.projectId = setting.getProjectId();
        this.cloudConnectionId = setting.getCloudConnectionId();
    }

    public static ProjectCloudConnectionSettingEntity from(ProjectCloudConnectionSetting setting) {
        return new ProjectCloudConnectionSettingEntity(setting);
    }

    public void updateFrom(ProjectCloudConnectionSetting setting) {
        this.cloudConnectionId = setting.getCloudConnectionId();
    }

    public ProjectCloudConnectionSetting toDomain() {
        return new ProjectCloudConnectionSetting(projectId, cloudConnectionId, createdAt, updatedAt);
    }
}
