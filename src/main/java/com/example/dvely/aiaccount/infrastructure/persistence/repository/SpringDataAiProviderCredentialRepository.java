package com.example.dvely.aiaccount.infrastructure.persistence.repository;

import com.example.dvely.aiaccount.infrastructure.persistence.entity.AiProviderCredentialEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataAiProviderCredentialRepository
        extends JpaRepository<AiProviderCredentialEntity, Long> {

    Optional<AiProviderCredentialEntity> findByUserIdAndProvider(Long userId, String provider);

    List<AiProviderCredentialEntity> findByUserIdOrderByProviderAsc(Long userId);

    long deleteByUserIdAndProvider(Long userId, String provider);
}
