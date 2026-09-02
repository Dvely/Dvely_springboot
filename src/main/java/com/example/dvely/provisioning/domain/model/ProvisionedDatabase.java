package com.example.dvely.provisioning.domain.model;

import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionOrigin;
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
    private final ProvisionOrigin origin;
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
    private Long approvalId;        // RDS 등 승인 대상만. 승인 핸들러가 이 값으로 대상 행을 찾는다.
    private Long cloudConnectionId; // RDS 만. 생성에 쓴 그 연결 — 상태 워커가 같은 계정으로 조회한다.

    public ProvisionedDatabase(Long id, Long projectId, ProvisionMethod method, DatabaseEngine engine,
                               ProvisionOrigin origin, ProvisionStatus status, String resourceId, String host, Integer port,
                               String databaseName, String username, String password,
                               LocalDateTime expiresAt, ProvisionFailureCode failureCode,
                               String errorMessage, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.method = method;
        this.engine = engine;
        this.origin = origin;
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

    /** 새 프로비저닝 요청 — PENDING 으로 시작. origin 으로 수동/자동을 구분한다. */
    public static ProvisionedDatabase pending(Long projectId, ProvisionMethod method, DatabaseEngine engine,
                                              ProvisionOrigin origin) {
        LocalDateTime now = LocalDateTime.now();
        return new ProvisionedDatabase(null, projectId, method, engine, origin, ProvisionStatus.PENDING,
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

    /**
     * 비동기 생성 시작(RDS). host 는 아직 없고, 상태 워커가 available 이 되면 markReady 로 채운다.
     * resourceId(인스턴스 ID)·port·접속계정은 지금 안다 — 워커가 그대로 쓸 수 있게 저장한다.
     * cloudConnectionId 는 생성에 쓴 그 연결이다 — 워커가 프로젝트의 '현재' 선택이 아니라 이 값으로
     * 조회해야, 도중에 연결이 바뀌어도 엉뚱한 계정으로 봐서 살아있는 인스턴스를 실패로 오판하지 않는다.
     */
    public void beginProvisioning(Long cloudConnectionId, String resourceId, int port,
                                  String databaseName, String username, String password) {
        this.status = ProvisionStatus.PROVISIONING;
        this.cloudConnectionId = cloudConnectionId;
        this.resourceId = resourceId;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 승인 대기 중 사용자가 거부함. FAILED 로 두되 failureCode 는 null 로 둔다 — 프로바이더 오류가
     * 아니므로(생성 자체를 시작하지 않았다), FE 가 문구를 오류가 아닌 "거부됨"으로 구분할 수 있다.
     */
    public void markRejected(String reason) {
        this.status = ProvisionStatus.FAILED;
        this.failureCode = null;
        this.errorMessage = reason;
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

    /** 이 프로비저닝을 특정 승인에 연결한다(RDS 처럼 승인 후 실행되는 경우). */
    public void linkApproval(Long approvalId) {
        this.approvalId = approvalId;
        this.updatedAt = LocalDateTime.now();
    }

    /** 영속 계층에서 로드 시 cloudConnectionId 를 복원한다(생성은 beginProvisioning 에서 세팅). */
    public void assignCloudConnection(Long cloudConnectionId) {
        this.cloudConnectionId = cloudConnectionId;
    }

    public boolean isExpiredByTime(LocalDateTime now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public ProvisionMethod getMethod() { return method; }
    public DatabaseEngine getEngine() { return engine; }
    public ProvisionOrigin getOrigin() { return origin; }
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
    public Long getApprovalId() { return approvalId; }
    public Long getCloudConnectionId() { return cloudConnectionId; }
}
