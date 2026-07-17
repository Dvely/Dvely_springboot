package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.infrastructure.persistence.entity.ProjectInfrastructureSettingChangeEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProjectInfrastructureSettingChangeRepository
        extends JpaRepository<ProjectInfrastructureSettingChangeEntity, Long> {

    Optional<ProjectInfrastructureSettingChangeEntity> findByApprovalId(Long approvalId);

    List<ProjectInfrastructureSettingChangeEntity> findByProjectIdAndStatusOrderByIdAsc(Long projectId, String status);

    // The trailing Pageable only supplies the offset/limit window (PageRequest.of(0, limit) from
    // the adapter) — ordering itself comes from the derived method name (created_at desc,
    // change_id desc), matching the domain repository contract.
    List<ProjectInfrastructureSettingChangeEntity> findByProjectIdOrderByCreatedAtDescIdDesc(
            Long projectId, Pageable pageable
    );
}
