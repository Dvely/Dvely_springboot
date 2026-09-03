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

    /** 원자적 claim: QUEUED 인 행만 BUILDING 으로 넘긴다. 진 워커 하나만 true — 이중 launch(과금) 방지. */
    boolean claimForBuild(Long id);

    /** 서버가 존재하는(했던) 클라우드 연결 ID 들. 고아 EIP 청소가 연결별로 계정을 훑을 때 쓴다. */
    List<Long> findDistinctCloudConnectionIds();

    /** 이 연결에 진행 중(QUEUED/BUILDING/PROVISIONING) 배포가 있는지 — 있으면 EIP 청소를 건너뛴다(경쟁 방지). */
    boolean existsInFlightByCloudConnectionId(Long cloudConnectionId);

    /** 이 연결에서 특정 상태 서버들이 소유한 EIP allocation ID 들. 청소가 살아있는 EIP 를 지키는 데 쓴다. */
    List<String> findElasticIpAllocationIds(Long cloudConnectionId, ServerStatus status);
}
