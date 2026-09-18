package com.example.dvely.aiaccount.domain.repository;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.domain.model.AiProviderCredential;
import java.util.List;
import java.util.Optional;

/**
 * Every lookup is scoped by {@code userId} on purpose: there is intentionally no
 * "find by id alone" or "find all" method, so no caller can reach another user's key by guessing
 * an id, and no code path can accidentally assemble a pool of keys across users (which the
 * providers' terms forbid).
 */
public interface AiProviderCredentialRepository {

    AiProviderCredential save(AiProviderCredential credential);

    Optional<AiProviderCredential> findByUserIdAndProvider(Long userId, AiProvider provider);

    List<AiProviderCredential> findByUserIdOrderByProviderAsc(Long userId);

    /** @return true when a row was actually removed, false when the user had no such credential. */
    boolean deleteByUserIdAndProvider(Long userId, AiProvider provider);
}
