package com.example.dvely.provisioning.domain.model;

import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import java.time.LocalDateTime;

/**
 * 프로비저닝된 DB 한 자원. 방식(LOCAL·RDS·DOCKER)·엔진·상태와 접속정보를 담는다.
 *
 * 비밀번호는 이 객체에 담아 옮기되(생성 직후 한 번 노출용), 조회 응답에는 절대 싣지 않는다 —
 * 환경변수 secret 선례와 같다. 저장 시에도 평문으로 두지 않고 AES 로 암호화한다(엔티티 계층).
 */
public class ProvisionedDatabase {

    private final Long id;
    private final Long projectId;
    private final ProvisionMethod method;
    private final DatabaseEngine engine;
    private ProvisionStatus status;
    private String resourceId;      // 컨테이너/인스턴스 식별자 — 정리 대상 지목
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private String password;        // 암호화 저장, 조회 응답엔 null
    private LocalDateTime expiresAt; // LOCAL 만 값
    private ProvisionFailureCode failureCode;
    private String errorMessage;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ProvisionedDatabase(Long id, Long projectId, ProvisionMethod method, DatabaseEngine engine,
                               ProvisionStatus status, String resourceId, String host, Integer port,
                               String databaseName, String username, String password,
                               LocalDateTime expiresAt, ProvisionFailureCode failureCode,
                               String errorMessage, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.method = method;
        this.engine = engine;
        this.status = status;
        this.resourceId = resourceId;
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;
        this.expiresAt = expiresAt;
        this.failureCode = failureCode;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 새 프로비저닝 요청 — PENDING 으로 시작. */
    public static ProvisionedDatabase pending(Long projectId, ProvisionMethod method, DatabaseEngine engine) {
        LocalDateTime now = LocalDateTime.now();
        return new ProvisionedDatabase(null, projectId, method, engine, ProvisionStatus.PENDING,
                null, null, null, null, null, null, null, null, null, now, now);
    }

    /** 프로비저닝 성공 — 접속정보를 채우고 READY. LOCAL 은 expiresAt 을 준다. */
    public void markReady(String resourceId, String host, int port, String databaseName,
                          String username, String password, LocalDateTime expiresAt) {
        this.status = ProvisionStatus.READY;
        this.resourceId = resourceId;
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;
        this.expiresAt = expiresAt;
        this.failureCode = null;
        this.errorMessage = null;
        this.updatedAt = LocalDateTime.now();
    }

    public void markProvisioning() {
        this.status = ProvisionStatus.PROVISIONING;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(ProvisionFailureCode code, String message) {
        this.status = ProvisionStatus.FAILED;
        this.failureCode = code;
        this.errorMessage = message;
        this.updatedAt = LocalDateTime.now();
    }

    /** LOCAL: 세션 만료로 사라짐. READY 로 두면 '화면엔 있는데 실제론 없는' 상태가 된다. */
    public void markExpired() {
        this.status = ProvisionStatus.EXPIRED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isExpiredByTime(LocalDateTime now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public ProvisionMethod getMethod() { return method; }
    public DatabaseEngine getEngine() { return engine; }
    public ProvisionStatus getStatus() { return status; }
    public String getResourceId() { return resourceId; }
    public String getHost() { return host; }
    public Integer getPort() { return port; }
    public String getDatabaseName() { return databaseName; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public ProvisionFailureCode getFailureCode() { return failureCode; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
