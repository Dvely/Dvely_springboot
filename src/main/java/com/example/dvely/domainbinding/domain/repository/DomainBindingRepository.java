package com.example.dvely.domainbinding.domain.repository;

import com.example.dvely.domainbinding.domain.model.DomainBinding;
import java.util.List;
import java.util.Optional;

public interface DomainBindingRepository {

    DomainBinding save(DomainBinding domainBinding);

    Optional<DomainBinding> findById(Long id);

    List<DomainBinding> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    boolean existsByHostnameIgnoreCase(String hostname);

    void deleteById(Long id);
}
