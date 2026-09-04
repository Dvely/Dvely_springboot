package com.example.dvely.provisioning.domain.model;

import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.domain.value.WebFrontendSpec;
import java.time.LocalDateTime;

/**
 * 프로비저닝된 EC2 백엔드 서버 한 자원. RDS 의 {@code ProvisionedDatabase} 와 형제다 — 과금 자원이라
 * 승인을 거치고, 생성이 비동기라 상태를 단계적으로 넘긴다. cloudConnectionId 를 기억하는 이유도 같다:
 * 상태 워커가 프로젝트의 '현재' 선택이 아니라 생성에 쓴 그 연결로 인스턴스를 조회해야, 도중에 연결이
 * 바뀌어도 엉뚱한 계정으로 봐서 살아있는 인스턴스를 오판(고아 과금)하지 않는다.
 */
public class ProvisionedServer {

    private final Long id;
    private final Long projectId;
    private final String instanceType;   // 예: t3.micro (설정 가능)
    private ServerStatus status;
    private Long cloudConnectionId;      // 생성에 쓴 연결. 워커가 같은 계정으로 조회한다.
    private String instanceId;           // EC2 인스턴스 ID — 정리 대상 지목
    // 실행 형태(NATIVE=java -jar / DOCKER=docker run). 기본 NATIVE. 생성자 밖: elasticIp 와 같은 이유로
    // 로드·설정 시 세팅(생성자 시그니처 churn 최소화). 모드 선택 배선(요청→submit)은 docker 경로에서.
    private ServerDeployMode deployMode = ServerDeployMode.NATIVE;
    // 번들 DB 엔진(null=없음). 있으면 DOCKER 배포가 같은 EC2 에 이 엔진의 DB 컨테이너를 docker compose 로
    // 함께 띄우고 앱을 그 DB 로 배선한다(RDS 없이 앱+DB 한 인스턴스). deployMode 와 같은 이유로 생성자 밖 세팅.
    private DatabaseEngine bundledDbEngine;
    // 웹(프론트) 컨테이너: 값이 있으면 같은 EC2 에 프론트 nginx 컨테이너를 compose 로 함께 띄운다. deployMode
    // 와 같은 이유로 생성자 밖 세팅. frontendRepo/frontendDir 중 하나라도 있으면 활성(hasWebFrontend).
    private String frontendRepo;
    private String frontendDir;
    private String apiPathPrefix;
    private String elasticIpAllocationId; // EIP 할당 ID — 종료 시 release 대상(생성자 밖: 로드·연결 시 세팅)
    private String publicHost;           // running 이후 채워짐
    private int port;                    // 앱 포트(기본 8080)
    private Long approvalId;             // 승인 대상 연결
    private ProvisionFailureCode failureCode;
    private String errorMessage;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ProvisionedServer(Long id, Long projectId, String instanceType, ServerStatus status,
                             Long cloudConnectionId, String instanceId, String publicHost, int port,
                             Long approvalId, ProvisionFailureCode failureCode, String errorMessage,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.instanceType = instanceType;
        this.status = status;
        this.cloudConnectionId = cloudConnectionId;
        this.instanceId = instanceId;
        this.publicHost = publicHost;
        this.port = port;
        this.approvalId = approvalId;
        this.failureCode = failureCode;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 새 서버 배포 요청 — PENDING 으로 시작(승인 대기). deployMode 는 실행 형태(null 이면 NATIVE),
     * bundledDbEngine 은 같은 EC2 에 함께 띄울 DB 엔진(null 이면 번들 DB 없음, DOCKER 모드에서만 유효).
     */
    public static ProvisionedServer pending(Long projectId, String instanceType, int port,
                                            ServerDeployMode deployMode, DatabaseEngine bundledDbEngine,
                                            WebFrontendSpec web) {
        LocalDateTime now = LocalDateTime.now();
        ProvisionedServer server = new ProvisionedServer(null, projectId, instanceType, ServerStatus.PENDING,
                null, null, null, port, null, null, null, now, now);
        server.assignDeployMode(deployMode);
        server.assignBundledDbEngine(bundledDbEngine);
        server.assignWebFrontend(web);
        return server;
    }

    /** 이 배포를 특정 승인에 연결한다(승인 후 실행되는 경우). */
    public void linkApproval(Long approvalId) {
        this.approvalId = approvalId;
        this.updatedAt = LocalDateTime.now();
    }

    /** 영속 계층 로드 시 cloudConnectionId 복원(생성 경로는 markQueued 에서 세팅). */
    public void assignCloudConnection(Long cloudConnectionId) {
        this.cloudConnectionId = cloudConnectionId;
    }

    /**
     * EIP 할당 ID 를 기록한다. 배포에서 EIP 를 연결한 뒤(안정 주소), 그리고 영속 계층 로드 시 복원할 때
     * 부른다. 종료 정리가 이 값으로 release 한다 — 없으면 유휴 EIP 가 계속 과금된다.
     */
    /** 실행 형태를 지정한다(로드 시 복원, 또는 배포 요청 시 선택). null 이면 NATIVE 유지. */
    public void assignDeployMode(ServerDeployMode deployMode) {
        if (deployMode != null) {
            this.deployMode = deployMode;
        }
    }

    /** 번들 DB 엔진을 지정한다(로드 시 복원, 또는 배포 요청 시 선택). null 이면 번들 DB 없음. */
    public void assignBundledDbEngine(DatabaseEngine bundledDbEngine) {
        this.bundledDbEngine = bundledDbEngine;
    }

    /** 웹 프론트 스펙을 지정한다(로드 시 복원, 또는 배포 요청 시 선택). null 이면 웹 컨테이너 없음. */
    public void assignWebFrontend(WebFrontendSpec web) {
        if (web != null) {
            this.frontendRepo = blankToNull(web.frontendRepo());
            this.frontendDir = blankToNull(web.frontendDir());
            this.apiPathPrefix = blankToNull(web.apiPathPrefix());
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    public void assignElasticIp(String elasticIpAllocationId) {
        this.elasticIpAllocationId = elasticIpAllocationId;   // updatedAt 은 건드리지 않음(로드 복원 겸용)
    }

    /**
     * 승인됨 — 배포 워커가 집을 수 있게 QUEUED 로 넘긴다. 무거운 빌드·launch 는 승인 트랜잭션에서
     * 하지 않고 워커가 한다. 생성에 쓸 연결을 여기서 기억한다.
     */
    public void markQueued(Long cloudConnectionId) {
        this.status = ServerStatus.QUEUED;
        this.cloudConnectionId = cloudConnectionId;
        this.updatedAt = LocalDateTime.now();
    }

    /** 워커가 빌드를 시작함. */
    public void markBuilding() {
        this.status = ServerStatus.BUILDING;
        this.updatedAt = LocalDateTime.now();
    }

    /** 인스턴스 생성됨 — running/헬스체크 대기. host 는 아직 없다. */
    public void beginProvisioning(String instanceId) {
        this.status = ServerStatus.PROVISIONING;
        this.instanceId = instanceId;
        this.updatedAt = LocalDateTime.now();
    }

    /** 헬스체크 통과 — 접속 가능. */
    public void markRunning(String publicHost) {
        this.status = ServerStatus.RUNNING;
        this.publicHost = publicHost;
        this.failureCode = null;
        this.errorMessage = null;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(ProvisionFailureCode code, String message) {
        this.status = ServerStatus.FAILED;
        this.failureCode = code;
        this.errorMessage = message;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 승인 대기 중 사용자가 거부함. FAILED 로 두되 failureCode 는 null — 프로바이더 오류가 아니므로
     * FE 가 '거부됨'으로 구분할 수 있다(ProvisionedDatabase.markRejected 와 동형).
     */
    public void markRejected(String reason) {
        this.status = ServerStatus.FAILED;
        this.failureCode = null;
        this.errorMessage = reason;
        this.updatedAt = LocalDateTime.now();
    }

    /** 종료됨 — 과금 정지. */
    public void markTerminated() {
        this.status = ServerStatus.TERMINATED;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public String getInstanceType() { return instanceType; }
    public ServerStatus getStatus() { return status; }
    public Long getCloudConnectionId() { return cloudConnectionId; }
    public String getInstanceId() { return instanceId; }
    public ServerDeployMode getDeployMode() { return deployMode; }
    public DatabaseEngine getBundledDbEngine() { return bundledDbEngine; }
    public boolean hasBundledDb() { return bundledDbEngine != null; }
    public String getFrontendRepo() { return frontendRepo; }
    public String getFrontendDir() { return frontendDir; }
    public String getApiPathPrefix() { return apiPathPrefix; }
    public WebFrontendSpec getWebFrontend() { return new WebFrontendSpec(frontendRepo, frontendDir, apiPathPrefix); }
    public boolean hasWebFrontend() { return frontendRepo != null || frontendDir != null; }
    public String getElasticIpAllocationId() { return elasticIpAllocationId; }
    public String getPublicHost() { return publicHost; }
    public int getPort() { return port; }
    public Long getApprovalId() { return approvalId; }
    public ProvisionFailureCode getFailureCode() { return failureCode; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
