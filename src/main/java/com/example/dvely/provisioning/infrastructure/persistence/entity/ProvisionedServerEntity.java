package com.example.dvely.provisioning.infrastructure.persistence.entity;

import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.domain.value.ServerStatus;
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
@Table(name = "provisioned_servers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProvisionedServerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "server_id")
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "instance_type", nullable = false, length = 32)
    private String instanceType;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "cloud_connection_id")
    private Long cloudConnectionId;

    @Column(name = "instance_id", length = 40)
    private String instanceId;

    @Column(name = "deploy_mode", nullable = false, length = 20)
    private String deployMode;

    @Column(name = "bundled_db_engine", length = 20)
    private String bundledDbEngine;   // null = 번들 DB 없음

    @Column(name = "elastic_ip_allocation_id", length = 40)
    private String elasticIpAllocationId;

    @Column(name = "public_host")
    private String publicHost;

    @Column(name = "port", nullable = false)
    private int port;

    @Column(name = "approval_id")
    private Long approvalId;

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

    public static ProvisionedServerEntity from(ProvisionedServer s) {
        ProvisionedServerEntity e = new ProvisionedServerEntity();
        e.id = s.getId();
        e.applyFrom(s);
        return e;
    }

    public void applyFrom(ProvisionedServer s) {
        this.projectId = s.getProjectId();
        this.instanceType = s.getInstanceType();
        this.status = s.getStatus().name();
        this.cloudConnectionId = s.getCloudConnectionId();
        this.instanceId = s.getInstanceId();
        this.deployMode = s.getDeployMode().name();
        this.bundledDbEngine = s.getBundledDbEngine() == null ? null : s.getBundledDbEngine().name();
        this.elasticIpAllocationId = s.getElasticIpAllocationId();
        this.publicHost = s.getPublicHost();
        this.port = s.getPort();
        this.approvalId = s.getApprovalId();
        this.failureCode = s.getFailureCode() == null ? null : s.getFailureCode().name();
        this.errorMessage = s.getErrorMessage();
    }

    public ProvisionedServer toDomain() {
        ProvisionedServer server = new ProvisionedServer(
                id, projectId, instanceType, ServerStatus.valueOf(status), cloudConnectionId,
                instanceId, publicHost, port, approvalId,
                failureCode == null ? null : ProvisionFailureCode.valueOf(failureCode),
                errorMessage, createdAt, updatedAt);
        server.assignDeployMode(deployMode == null ? null : ServerDeployMode.valueOf(deployMode));
        server.assignBundledDbEngine(bundledDbEngine == null ? null : DatabaseEngine.valueOf(bundledDbEngine));
        server.assignElasticIp(elasticIpAllocationId);
        return server;
    }
}
