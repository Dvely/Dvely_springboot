package com.example.dvely.provisioning.infrastructure.persistence.repository;

import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import com.example.dvely.provisioning.infrastructure.persistence.entity.ProvisionedDatabaseEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ProvisionedDatabaseRepositoryAdapter implements ProvisionedDatabaseRepository {

    private final SpringDataProvisionedDatabaseRepository springDataRepository;

    @Override
    public ProvisionedDatabase save(ProvisionedDatabase database) {
        if (database.getId() == null) {
            return springDataRepository.save(ProvisionedDatabaseEntity.from(database)).toDomain();
        }
        ProvisionedDatabaseEntity entity = springDataRepository.findById(database.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "프로비저닝 자원을 찾을 수 없습니다. id=" + database.getId()));
        entity.applyFrom(database);
        return springDataRepository.save(entity).toDomain();
    }

    @Override
    public Optional<ProvisionedDatabase> findById(Long id) {
        return springDataRepository.findById(id).map(ProvisionedDatabaseEntity::toDomain);
    }

    @Override
    public List<ProvisionedDatabase> findByProjectIdOrderByCreatedAtDesc(Long projectId) {
        return springDataRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream().map(ProvisionedDatabaseEntity::toDomain).toList();
    }

    @Override
    public List<ProvisionedDatabase> findActiveByProjectIdOrderByCreatedAtDesc(Long projectId) {
        return springDataRepository.findByProjectIdAndStatusNotOrderByCreatedAtDesc(
                        projectId, ProvisionStatus.EXPIRED.name())
                .stream().map(ProvisionedDatabaseEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public boolean claimForExpiry(Long id, LocalDateTime now) {
        return springDataRepository.claimExpired(id, now) == 1;
    }

    @Override
    public List<ProvisionedDatabase> findExpirable(LocalDateTime now, int limit) {
        return springDataRepository.findByStatusAndExpiresAtBefore(
                        ProvisionStatus.READY.name(), now, PageRequest.of(0, limit))
                .stream().map(ProvisionedDatabaseEntity::toDomain).toList();
    }

    @Override
    public List<ProvisionedDatabase> findByStatus(ProvisionStatus status, int limit) {
        return springDataRepository.findByStatusOrderByCreatedAtAsc(status.name(), PageRequest.of(0, limit))
                .stream().map(ProvisionedDatabaseEntity::toDomain).toList();
    }
}
