package com.example.dvely.approval.infrastructure.persistence.repository;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.infrastructure.persistence.entity.ApprovalEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ApprovalRepositoryAdapter implements ApprovalRepository {

    private final SpringDataApprovalRepository springDataRepository;

    @Override
    public Approval save(Approval approval) {
        if (approval.getId() == null) {
            return springDataRepository.save(ApprovalEntity.from(approval)).toDomain();
        }
        ApprovalEntity entity = springDataRepository.findById(approval.getId())
                .orElseThrow(() -> new IllegalStateException("승인을 찾을 수 없습니다. approvalId=" + approval.getId()));
        entity.updateFrom(approval);
        return springDataRepository.save(entity).toDomain();
    }

    @Override
    public Optional<Approval> findByIdAndOwnerUserId(Long approvalId, Long ownerUserId) {
        return springDataRepository.findByIdAndOwnerUserId(approvalId, ownerUserId)
                .map(ApprovalEntity::toDomain);
    }

    @Override
    public Optional<Approval> findByIdAndOwnerUserIdForUpdate(Long approvalId, Long ownerUserId) {
        return springDataRepository.findByIdAndOwnerUserIdForUpdate(approvalId, ownerUserId)
                .map(ApprovalEntity::toDomain);
    }

    @Override
    public List<Approval> findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(Long projectId, Long ownerUserId) {
        return springDataRepository.findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(projectId, ownerUserId)
                .stream()
                .map(ApprovalEntity::toDomain)
                .toList();
    }

    @Override
    public List<Approval> findByTaskIdOrderByIdAsc(String taskId) {
        return springDataRepository.findByTaskIdOrderByIdAsc(taskId)
                .stream()
                .map(ApprovalEntity::toDomain)
                .toList();
    }
}
