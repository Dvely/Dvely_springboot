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

    @Column(name = "frontend_repo", length = 255)
    private String frontendRepo;      // null = 웹 컨테이너 없음(모노면 frontend_dir 로만 활성)

    @Column(name = "frontend_dir", length = 255)
    private String frontendDir;

    @Column(name = "web_only", nullable = false)
    private boolean webOnly;

    @Column(name = "supersedes_server_id")
    private Long supersedesServerId;   // 재배포 교체 대상(이전 서버). null = 최초 배포

    @Column(name = "api_path_prefix", length = 255)
    private String apiPathPrefix;

    @Column(name = "elastic_ip_allocation_id", length = 40)
    private String elasticIpAllocationId;

    @Column(name = "public_host")
    private String publicHost;

    @Column(name = "healthy")
    private Boolean healthy;   // RUNNING 이후 앱 건강(주기 TCP 헬스체크). null=미확인

    @Column(name = "last_health_check_at")
    private java.time.LocalDateTime lastHealthCheckAt;

    @Column(name = "boot_diagnostics", columnDefinition = "TEXT")
    private String bootDiagnostics;   // 부트 타임아웃 종료 직전 보존한 부트 로그. 정상 서버는 null

    @Column(name = "recovery_attempted_at")
    private java.time.LocalDateTime recoveryAttemptedAt;   // 무응답 자동복구 시도 시각. 회복되면 null

    // 다중 인스턴스 리스(교체 워커 claim). 순수 인프라 — 도메인 모델·applyFrom 에 싣지 않아 도메인 저장
    // (findById→applyFrom→save)이 이 값을 건드리지 않고 보존한다. claim/해제는 전용 UPDATE 로만 한다.
    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private java.time.LocalDateTime leaseUntil;

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
        this.frontendRepo = s.getFrontendRepo();
        this.frontendDir = s.getFrontendDir();
        this.apiPathPrefix = s.getApiPathPrefix();
        this.webOnly = s.isWebOnly();
        this.supersedesServerId = s.getSupersedesServerId();
        this.elasticIpAllocationId = s.getElasticIpAllocationId();
        this.publicHost = s.getPublicHost();
        this.healthy = s.getHealthy();
        this.lastHealthCheckAt = s.getLastHealthCheckAt();
        this.bootDiagnostics = s.getBootDiagnostics();
        this.recoveryAttemptedAt = s.getRecoveryAttemptedAt();
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
        server.assignWebFrontend(new com.example.dvely.provisioning.domain.value.WebFrontendSpec(
                frontendRepo, frontendDir, apiPathPrefix));
        server.assignWebOnly(webOnly);
        server.assignSupersedes(supersedesServerId);
        server.assignElasticIp(elasticIpAllocationId);
        server.restoreHealth(healthy, lastHealthCheckAt);
        server.restoreBootDiagnostics(bootDiagnostics);
        server.restoreRecoveryAttemptedAt(recoveryAttemptedAt);
        return server;
    }
}
