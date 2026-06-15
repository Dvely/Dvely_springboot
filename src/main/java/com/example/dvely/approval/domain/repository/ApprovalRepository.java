package com.example.dvely.approval.domain.repository;

import com.example.dvely.approval.domain.model.Approval;
import java.util.List;
import java.util.Optional;

public interface ApprovalRepository {

    Approval save(Approval approval);

    Optional<Approval> findByIdAndOwnerUserId(Long approvalId, Long ownerUserId);

    List<Approval> findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(Long projectId, Long ownerUserId);

    List<Approval> findByTaskIdOrderByIdAsc(String taskId);
}
