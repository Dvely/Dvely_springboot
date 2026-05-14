package com.example.dvely.domainbinding.infrastructure.persistence.repository;

import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.infrastructure.persistence.entity.DomainBindingEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DomainBindingRepositoryAdapter implements DomainBindingRepository {

    private final SpringDataDomainBindingRepository springDataRepository;

    @Override
    public DomainBinding save(DomainBinding domainBinding) {
        if (domainBinding.getId() == null) {
            return springDataRepository.save(DomainBindingEntity.from(domainBinding)).toDomain();
        }
        DomainBindingEntity entity = springDataRepository.findById(domainBinding.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "도메인 정보를 찾을 수 없습니다. domainId=" + domainBinding.getId()));
        entity.updateFrom(domainBinding);
        return springDataRepository.save(entity).toDomain();
    }

    @Override
    public Optional<DomainBinding> findById(Long id) {
        return springDataRepository.findById(id).map(DomainBindingEntity::toDomain);
    }

    @Override
    public List<DomainBinding> findByProjectIdOrderByCreatedAtDesc(Long projectId) {
        return springDataRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(DomainBindingEntity::toDomain)
                .toList();
    }

    @Override
    public boolean existsByHostnameIgnoreCase(String hostname) {
        return springDataRepository.existsByHostnameIgnoreCase(hostname);
    }

    @Override
    public void deleteById(Long id) {
        springDataRepository.deleteById(id);
    }
}
