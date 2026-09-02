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
    @Transactional
    public boolean claimForBuild(Long id) {
        return springDataRepository.claimForBuild(id, LocalDateTime.now()) == 1;
    }
}
