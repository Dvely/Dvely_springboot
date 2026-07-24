package com.example.dvely.audit.infrastructure.persistence.repository;

import com.example.dvely.audit.domain.model.AuditLog;
import com.example.dvely.audit.domain.repository.AuditLogRepository;
import com.example.dvely.audit.domain.value.AuditCategory;
import com.example.dvely.audit.infrastructure.persistence.entity.AuditLogEntity;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class AuditLogRepositoryAdapter implements AuditLogRepository {

    private final SpringDataAuditLogRepository springDataRepository;

    @Override
    public AuditLog save(AuditLog auditLog) {
        return springDataRepository.save(AuditLogEntity.from(auditLog)).toDomain();
    }

    @Override
    public List<AuditLog> findByProjectIdOrderByIdDesc(Long projectId, int limit) {
        return springDataRepository.findByProjectIdOrderByIdDesc(projectId, PageRequest.of(0, limit))
                .stream()
                .map(AuditLogEntity::toDomain)
                .toList();
    }

    @Override
    public List<AuditLog> findByProjectIdAndCategoryOrderByIdDesc(Long projectId, AuditCategory category, int limit) {
        return springDataRepository
                .findByProjectIdAndCategoryOrderByIdDesc(projectId, category.name(), PageRequest.of(0, limit))
                .stream()
                .map(AuditLogEntity::toDomain)
                .toList();
    }

    /**
     * {@code @Transactional} here is not optional decoration: {@code AuditLogRetentionScheduler}
     * calls this with no ambient transaction (it is a plain {@code @Scheduled} method, design §8),
     * and Spring Data JPA's repository proxy does not open one automatically for a custom
     * {@code @Modifying} query the way it does for the built-in CRUD methods — without this
     * annotation, the {@code @Modifying(flushAutomatically = true)} on
     * {@link SpringDataAuditLogRepository#deleteBatch} throws
     * {@code InvalidDataAccessApiUsageException} ("No EntityManager with actual transaction
     * available") the moment it tries to flush.
     */
    @Override
    @Transactional
    public int deleteBatch(LocalDateTime cutoff, int batchSize) {
        return springDataRepository.deleteBatch(cutoff, batchSize);
    }
}
