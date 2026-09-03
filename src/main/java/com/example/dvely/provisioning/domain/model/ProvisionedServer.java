package com.example.dvely.provisioning.domain.model;

import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.ServerStatus;
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

    /** 새 서버 배포 요청 — PENDING 으로 시작(승인 대기). */
    public static ProvisionedServer pending(Long projectId, String instanceType, int port) {
        LocalDateTime now = LocalDateTime.now();
        return new ProvisionedServer(null, projectId, instanceType, ServerStatus.PENDING,
                null, null, null, port, null, null, null, now, now);
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
    public String getElasticIpAllocationId() { return elasticIpAllocationId; }
    public String getPublicHost() { return publicHost; }
    public int getPort() { return port; }
    public Long getApprovalId() { return approvalId; }
    public ProvisionFailureCode getFailureCode() { return failureCode; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
