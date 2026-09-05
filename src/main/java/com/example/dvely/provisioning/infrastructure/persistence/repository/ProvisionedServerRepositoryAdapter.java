package com.example.dvely.provisioning.infrastructure.persistence.repository;

import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.persistence.entity.ProvisionedServerEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ProvisionedServerRepositoryAdapter implements ProvisionedServerRepository {

    private final SpringDataProvisionedServerRepository springDataRepository;

    @Override
    public ProvisionedServer save(ProvisionedServer server) {
        if (server.getId() == null) {
            return springDataRepository.save(ProvisionedServerEntity.from(server)).toDomain();
        }
        ProvisionedServerEntity entity = springDataRepository.findById(server.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "프로비저닝 서버를 찾을 수 없습니다. id=" + server.getId()));
        entity.applyFrom(server);
        return springDataRepository.save(entity).toDomain();
    }

    @Override
    public Optional<ProvisionedServer> findById(Long id) {
        return springDataRepository.findById(id).map(ProvisionedServerEntity::toDomain);
    }

    @Override
    public Optional<ProvisionedServer> findByApprovalId(Long approvalId) {
        return springDataRepository.findByApprovalId(approvalId).map(ProvisionedServerEntity::toDomain);
    }

    @Override
    public List<ProvisionedServer> findByProjectIdOrderByCreatedAtDesc(Long projectId) {
        return springDataRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream().map(ProvisionedServerEntity::toDomain).toList();
    }

    @Override
    public List<ProvisionedServer> findByStatus(ServerStatus status, int limit) {
        return springDataRepository.findByStatusOrderByCreatedAtAsc(status.name(), PageRequest.of(0, limit))
                .stream().map(ProvisionedServerEntity::toDomain).toList();
    }

    @Override
    public List<ProvisionedServer> findRunningWithPendingReplacement(int limit) {
        return springDataRepository.findByStatusAndSupersedesServerIdIsNotNullOrderByCreatedAtAsc(
                        ServerStatus.RUNNING.name(), PageRequest.of(0, limit))
                .stream().map(ProvisionedServerEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public boolean claimForBuild(Long id) {
        return springDataRepository.claimForBuild(id, LocalDateTime.now()) == 1;
    }

    /** 교체 리스 유지 시간. 한 틱의 교체 처리보다 넉넉하되, 인스턴스가 죽으면 다른 곳이 이어받을 만큼 짧게. */
    private static final java.time.Duration REPLACEMENT_LEASE = java.time.Duration.ofMinutes(2);

    @Override
    @Transactional
    public boolean claimForReplacement(Long id, String owner) {
        LocalDateTime now = LocalDateTime.now();
        return springDataRepository.claimForReplacement(id, owner, now.plus(REPLACEMENT_LEASE), now) == 1;
    }

    @Override
    @Transactional
    public boolean claimBootTimeout(Long id) {
        return springDataRepository.claimBootTimeout(id, LocalDateTime.now()) == 1;
    }

    @Override
    public List<Long> findDistinctCloudConnectionIds() {
        return springDataRepository.findDistinctCloudConnectionIds();
    }

    @Override
    public boolean existsInFlightByCloudConnectionId(Long cloudConnectionId) {
        return springDataRepository.countInFlightByCloudConnectionId(cloudConnectionId) > 0;
    }

    @Override
    public List<String> findElasticIpAllocationIds(Long cloudConnectionId, ServerStatus status) {
        return springDataRepository.findElasticIpAllocationIds(cloudConnectionId, status.name());
    }
}
