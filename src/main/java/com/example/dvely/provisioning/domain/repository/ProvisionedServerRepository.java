package com.example.dvely.provisioning.domain.repository;

import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import java.util.List;
import java.util.Optional;

public interface ProvisionedServerRepository {

    ProvisionedServer save(ProvisionedServer server);

    Optional<ProvisionedServer> findById(Long id);

    /** 승인 대상을 그 승인 ID 로 찾는다. 승인 핸들러가 대상 행을 집을 때 쓴다. */
    Optional<ProvisionedServer> findByApprovalId(Long approvalId);

    List<ProvisionedServer> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** 워커가 집을 대상(QUEUED·PROVISIONING 등). */
    List<ProvisionedServer> findByStatus(ServerStatus status, int limit);

    /** RUNNING 이 됐지만 아직 교체 대상(이전 서버)을 정리하지 않은 서버 — 리플레이스 워커가 집는다. */
    List<ProvisionedServer> findRunningWithPendingReplacement(int limit);

    /** 원자적 claim: QUEUED 인 행만 BUILDING 으로 넘긴다. 진 워커 하나만 true — 이중 launch(과금) 방지. */
    boolean claimForBuild(Long id);

    /**
     * 재배포 교체 워커의 다중 인스턴스 리스 claim. 리스가 비었거나 만료됐거나 내가 쥔 것이면 true —
     * 그때만 이 서버의 EIP 재연결·종료를 진행한다(두 인스턴스가 동시에 못 하게). 같은 owner 는 여러 틱에
     * 걸쳐 이어받는다.
     */
    boolean claimForReplacement(Long id, String owner);

    /**
     * 부트 타임아웃 처리 권한 claim(PROVISIONING→FAILED status-CAS). 진 인스턴스 하나만 true — 그것만
     * 인스턴스를 종료하고 부트 로그를 뜬다(중복 terminate·SSM 방지).
     */
    boolean claimBootTimeout(Long id);

    /** 서버가 존재하는(했던) 클라우드 연결 ID 들. 고아 EIP 청소가 연결별로 계정을 훑을 때 쓴다. */
    List<Long> findDistinctCloudConnectionIds();

    /** 이 연결에 진행 중(QUEUED/BUILDING/PROVISIONING) 배포가 있는지 — 있으면 EIP 청소를 건너뛴다(경쟁 방지). */
    boolean existsInFlightByCloudConnectionId(Long cloudConnectionId);

    /** 이 연결에서 특정 상태 서버들이 소유한 EIP allocation ID 들. 청소가 살아있는 EIP 를 지키는 데 쓴다. */
    List<String> findElasticIpAllocationIds(Long cloudConnectionId, ServerStatus status);
}
