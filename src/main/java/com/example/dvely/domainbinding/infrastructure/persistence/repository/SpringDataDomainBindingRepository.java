package com.example.dvely.domainbinding.infrastructure.persistence.repository;

import com.example.dvely.domainbinding.infrastructure.persistence.entity.DomainBindingEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataDomainBindingRepository extends JpaRepository<DomainBindingEntity, Long> {

    List<DomainBindingEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    // status 컬럼은 enum 이 아니라 String 이다(DomainBindingEntity:46).
    List<DomainBindingEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    boolean existsByHostnameIgnoreCase(String hostname);
}
