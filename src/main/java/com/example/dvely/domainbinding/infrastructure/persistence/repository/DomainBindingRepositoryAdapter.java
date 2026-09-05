package com.example.dvely.domainbinding.infrastructure.persistence.repository;

import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.infrastructure.persistence.entity.DomainBindingEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
    public List<DomainBinding> findByStatus(DomainStatus status, int limit) {
        return springDataRepository
                .findByStatusOrderByCreatedAtAsc(status.name(), PageRequest.of(0, limit))
                .stream()
                .map(DomainBindingEntity::toDomain)
                .toList();
    }

    /** CDN 프로비전 리스 유지 시간. 인증서 발급(~수 분)·배포 생성은 여러 틱에 걸쳐 매 틱 재claim 한다. */
    private static final java.time.Duration CDN_PROVISION_LEASE = java.time.Duration.ofMinutes(2);

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean claimForCdnProvision(Long id, String owner) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return springDataRepository.claimForCdnProvision(id, owner, now.plus(CDN_PROVISION_LEASE), now) == 1;
    }

    @Override
    public boolean existsByHostnameIgnoreCase(String hostname) {
        return springDataRepository.existsByHostnameIgnoreCase(hostname);
    }

    @Override
    public void deleteById(Long id) {
        springDataRepository.deleteById(id);
    }

    @Override
    public boolean existsByHostnameIgnoreCaseAndHostingTarget(String hostname,
            com.example.dvely.domainbinding.domain.value.DomainHostingTarget hostingTarget) {
        return springDataRepository.existsByHostnameIgnoreCaseAndHostingTarget(hostname, hostingTarget.name());
    }
}