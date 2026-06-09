package com.example.dvely.change.infrastructure.persistence.repository;

import com.example.dvely.change.infrastructure.persistence.entity.ChangeEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataChangeRepository extends JpaRepository<ChangeEntity, Long> {

    Optional<ChangeEntity> findByTaskId(String taskId);

    Optional<ChangeEntity> findByIdAndOwnerUserId(Long changeId, Long ownerUserId);

    List<ChangeEntity> findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(Long projectId, Long ownerUserId);
}
