package com.example.dvely.aiaccount.infrastructure.persistence.repository;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.domain.model.AiProviderCredential;
import com.example.dvely.aiaccount.domain.repository.AiProviderCredentialRepository;
import com.example.dvely.aiaccount.infrastructure.persistence.entity.AiProviderCredentialEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AiProviderCredentialRepositoryAdapter implements AiProviderCredentialRepository {

    private final SpringDataAiProviderCredentialRepository springDataRepository;

    @Override
    public AiProviderCredential save(AiProviderCredential credential) {
        if (credential.getId() == null) {
            // saveAndFlush forces the INSERT (and its (user, provider) unique-constraint check) to
            // happen here rather than at transaction commit — otherwise a concurrent duplicate
            // registration would surface as a DataIntegrityViolationException only after the
            // calling service has returned, where it can no longer be translated into a clean
            // 409 (mirrors EnvironmentVariableRepositoryAdapter's reasoning).
            return springDataRepository.saveAndFlush(AiProviderCredentialEntity.from(credential)).toDomain();
        }
        AiProviderCredentialEntity entity = springDataRepository.findById(credential.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "AI 제공자 크리덴셜을 찾을 수 없습니다. id=" + credential.getId()));
        entity.updateFrom(credential);
        // saveAndFlush here too: a plain save() would only schedule the UPDATE for the next flush,
        // and @UpdateTimestamp is populated during that flush — so toDomain() below would read
        // back a stale updatedAt and the rotation response would show the wrong timestamp.
        return springDataRepository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<AiProviderCredential> findByUserIdAndProvider(Long userId, AiProvider provider) {
        return springDataRepository.findByUserIdAndProvider(userId, provider.name())
                .map(AiProviderCredentialEntity::toDomain);
    }

    @Override
    public List<AiProviderCredential> findByUserIdOrderByProviderAsc(Long userId) {
        return springDataRepository.findByUserIdOrderByProviderAsc(userId)
                .stream()
                .map(AiProviderCredentialEntity::toDomain)
                .toList();
    }

    @Override
    public boolean deleteByUserIdAndProvider(Long userId, AiProvider provider) {
        return springDataRepository.deleteByUserIdAndProvider(userId, provider.name()) > 0;
    }
}
