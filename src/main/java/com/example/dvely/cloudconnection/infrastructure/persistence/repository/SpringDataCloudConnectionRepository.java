package com.example.dvely.cloudconnection.infrastructure.persistence.repository;

import com.example.dvely.cloudconnection.infrastructure.persistence.entity.CloudConnectionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataCloudConnectionRepository extends JpaRepository<CloudConnectionEntity, Long> {

    List<CloudConnectionEntity> findAllByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    Optional<CloudConnectionEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);
}
