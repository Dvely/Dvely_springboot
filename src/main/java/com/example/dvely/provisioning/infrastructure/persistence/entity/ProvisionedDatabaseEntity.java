package com.example.dvely.provisioning.infrastructure.persistence.entity;

import com.example.dvely.auth.infrastructure.persistence.converter.AesEncryptor;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionOrigin;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
@Table(name = "provisioned_databases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProvisionedDatabaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "database_id")
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "method", nullable = false, length = 20)
    private String method;

    @Column(name = "origin", nullable = false, length = 20)
    private String origin;

    @Column(name = "engine", nullable = false, length = 20)
    private String engine;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "host")
    private String host;

    @Column(name = "port")
    private Integer port;

    @Column(name = "database_name", length = 64)
    private String databaseName;

    @Column(name = "username", length = 64)
    private String username;

    // cloud_connections 의 시크릿과 같은 방식으로 컬럼 암호화.
    @Convert(converter = AesEncryptor.class)
    @Column(name = "password", columnDefinition = "MEDIUMTEXT")
    private String password;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "cloud_connection_id")
    private Long cloudConnectionId;

    public static ProvisionedDatabaseEntity from(ProvisionedDatabase d) {
        ProvisionedDatabaseEntity e = new ProvisionedDatabaseEntity();
        e.id = d.getId();
        e.applyFrom(d);
        return e;
    }

    public void applyFrom(ProvisionedDatabase d) {
        this.projectId = d.getProjectId();
        this.method = d.getMethod().name();
        this.origin = d.getOrigin().name();
        this.engine = d.getEngine().name();
        this.status = d.getStatus().name();
        this.resourceId = d.getResourceId();
        this.host = d.getHost();
        this.port = d.getPort();
        this.databaseName = d.getDatabaseName();
        this.username = d.getUsername();
        this.password = d.getPassword();
        this.expiresAt = d.getExpiresAt();
        this.failureCode = d.getFailureCode() == null ? null : d.getFailureCode().name();
        this.errorMessage = d.getErrorMessage();
        this.approvalId = d.getApprovalId();
        this.cloudConnectionId = d.getCloudConnectionId();
    }

    public ProvisionedDatabase toDomain() {
        ProvisionedDatabase domain = new ProvisionedDatabase(
                id, projectId, ProvisionMethod.valueOf(method), DatabaseEngine.valueOf(engine),
                ProvisionOrigin.valueOf(origin), ProvisionStatus.valueOf(status), resourceId, host, port, databaseName, username,
                password, expiresAt,
                failureCode == null ? null : ProvisionFailureCode.valueOf(failureCode),
                errorMessage, createdAt, updatedAt);
        if (approvalId != null) {
            domain.linkApproval(approvalId);
        }
        if (cloudConnectionId != null) {
            domain.assignCloudConnection(cloudConnectionId);
        }
        return domain;
    }
}
