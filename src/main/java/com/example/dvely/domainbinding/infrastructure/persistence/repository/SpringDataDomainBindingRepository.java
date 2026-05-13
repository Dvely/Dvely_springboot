package com.example.dvely.domainbinding.infrastructure.persistence.repository;

import com.example.dvely.domainbinding.infrastructure.persistence.entity.DomainBindingEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataDomainBindingRepository extends JpaRepository<DomainBindingEntity, Long> {

    List<DomainBindingEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    boolean existsByHostnameIgnoreCase(String hostname);
}
