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
}
